package net.servicestack.android;

import android.os.Build;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

import net.servicestack.client.JsonUtils;
import net.servicestack.client.Log;
import net.servicestack.client.Utils;
import net.servicestack.client.sse.ExceptionCallback;
import net.servicestack.client.sse.GetEventSubscribers;
import net.servicestack.client.sse.ServerEventCallback;
import net.servicestack.client.sse.ServerEventConnect;
import net.servicestack.client.sse.ServerEventConnectCallback;
import net.servicestack.client.sse.ServerEventJoin;
import net.servicestack.client.sse.ServerEventLeave;
import net.servicestack.client.sse.ServerEventMessage;
import net.servicestack.client.sse.ServerEventMessageCallback;
import net.servicestack.client.sse.ServerEventUser;
import net.servicestack.client.sse.ServerEventsClient;
import net.servicestack.client.sse.SingletonInstanceResolver;
import net.servicestack.func.Action;
import net.servicestack.func.Func;
import net.servicestack.func.Predicate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static chat.chatdtos.ChatMessage;
import static chat.chatdtos.CustomType;
import static chat.chatdtos.PostChatToChannel;
import static chat.chatdtos.PostObjectToChannel;
import static chat.chatdtos.PostRawToChannel;
import static chat.chatdtos.ResetServerEvents;
import static chat.chatdtos.SetterType;

/**
 * Created by mythz on 2/10/2017.
 */

@android.support.annotation.RequiresApi(api = Build.VERSION_CODES.KITKAT)
@RunWith(AndroidJUnit4.class)
public class ServerEventClientTests {

    public ServerEventClientTests() {
        Log.setInstance(new AndroidLogProvider("ZZZ", true));
    }

    public ServerEventsClient createServerEventsClient(String baseUrl, String... channels){
        return new AndroidServerEventsClient(baseUrl, channels);
    }

    /* Project wont build if using Java 8 lambda's so replaced to use anon class */

    @Test
    public void Can_connect_to_ServerEventsStream() throws Exception {
        final CountDownLatch signal = new CountDownLatch(1);

        try (ServerEventsClient client = createServerEventsClient("http://chat.servicestack.net", "home")
                .setOnConnect(new ServerEventConnectCallback() {
                    @Override
                    public void execute(ServerEventConnect e) {
                        System.out.print("onConnect: " + e);
                        signal.countDown();
                    }
                }).start())
        {
            assertTrue(signal.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void Does_fire_onJoin_events() throws Exception {
        final CountDownLatch signal = new CountDownLatch(1);

        try(ServerEventsClient client = createServerEventsClient("http://chat.servicestack.net", "home"))
        {
            client
                .setOnConnect(new ServerEventConnectCallback() {
                    @Override
                    public void execute(ServerEventConnect e) {
                        assertTrue(e.getHeartbeatUrl().startsWith("http://chat.servicestack.net"));
                    }
                })
                .setOnCommand(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        System.out.print("onCommand: " + e);
                        assertTrue(e instanceof ServerEventJoin);
                        ServerEventJoin joinMsg = (ServerEventJoin) e;
                        assertEquals(client.getConnectionInfo().getDisplayName(), joinMsg.getDisplayName());
                        signal.countDown();
                    }
                })
                .start();

            assertTrue(signal.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void Does_fire_onJoin_events_for_multiple_Channels() throws Exception {
        final CountDownLatch signal = new CountDownLatch(1);

        final String[] channels = new String[] { "A", "B", "C" };
        final List<ServerEventJoin> joinMsgs = new ArrayList<>();

        try (ServerEventsClient client = createServerEventsClient("http://chat.servicestack.net", channels))
        {
            client
                .setOnCommand(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        System.out.print("onCommand: " + e);

                        if (e instanceof ServerEventJoin) {
                            ServerEventJoin joinMsg = (ServerEventJoin) e;
                            joinMsgs.add(joinMsg);

                            assertEquals(channels[joinMsgs.size() - 1], joinMsg.getChannel());
                            assertEquals(client.getConnectionInfo().getDisplayName(), joinMsg.getDisplayName());

                            if (joinMsgs.size() == channels.length)
                                signal.countDown();
                        }
                    }
                })
                .start();

            assertTrue(signal.await(5, TimeUnit.SECONDS));
        }
    }

    private void clearPreviousRun(String[] channels) throws Exception {
        final CountDownLatch signal = new CountDownLatch(1);

        try (ServerEventsClient client = createServerEventsClient("http://chat.servicestack.net", channels)
                .setOnConnect(new ServerEventConnectCallback() {
                    @Override
                    public void execute(ServerEventConnect e) {
                        signal.countDown();
                    }
                })
                .start())
        {
            assertTrue(signal.await(5, TimeUnit.SECONDS));
        }
    }

//    @Test
    public void ignore_test_ClearPreviousRun() throws Exception {
        clearPreviousRun(null);
    }

    @Test
    public void Does_fire_all_callbacks() throws Exception {
//        clearPreviousRun(null);

        final List<ServerEventConnect> connectMsgs = new ArrayList<>();
        final List<ServerEventMessage> msgs = new ArrayList<>();
        final List<ServerEventMessage> commands = new ArrayList<>();
        final List<Exception> errors = new ArrayList<>();

        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
            .setOnConnect(new ServerEventConnectCallback() {
                @Override
                public void execute(ServerEventConnect e) {
                    connectMsgs.add(e);
                }
            })
            .setOnCommand(new ServerEventMessageCallback() {
                @Override
                public void execute(ServerEventMessage e) {
                    commands.add(e);
                }
            })
            .setOnMessage(new ServerEventMessageCallback() {
                @Override
                public void execute(ServerEventMessage e) {
                    msgs.add(e);
                }
            })
            .setOnException(new ExceptionCallback() {
                @Override
                public void execute(Exception e) {
                    errors.add(e);
                }
            })
            .start())
        {
            while (connectMsgs.size() < 1 || commands.size() < 1) {
                Thread.sleep(100);
            }

            ServerEventConnect connectMsg = Func.first(connectMsgs);
            ServerEventJoin joinMsg = Func.first(Func.ofType(commands, ServerEventJoin.class));
            assertNotNull(connectMsg);
            assertNotNull(joinMsg);

            assertEquals(0, msgs.size());
            assertEquals(0, errors.size());
            assertEquals(1, commands.size()); //join

            connectMsgs.clear();
            commands.clear();

            try(ServerEventsClient client2 = createServerEventsClient("http://chat.servicestack.net")
                    .setOnConnect(new ServerEventConnectCallback() {
                        @Override
                        public void execute(ServerEventConnect e) {
                            connectMsgs.add(e);
                        }
                    })
                    .start())
            {
                while (connectMsgs.size() < 1 || commands.size() < 1) {
                    Thread.sleep(100);
                }

                connectMsg = Func.first(connectMsgs);
                joinMsg = Func.first(Func.ofType(commands, ServerEventJoin.class));
            }

            while (commands.size() < 2) {
                Thread.sleep(100);
            }

            ServerEventLeave leaveMsg = Func.first(Func.ofType(commands, ServerEventLeave.class));

            assertNotNull(joinMsg);
            assertNotNull(leaveMsg);
            assertTrue(commands.size() >= 2);
            assertEquals(errors.size(), 0);
        }
    }

    private void postChat(ServerEventsClient client, String message) {
        postChat(client, message, null);
    }

    private void postChat(ServerEventsClient client, String message, String channel) {
        client.getServiceClient().post(new PostChatToChannel()
            .setFrom(client.getSubscriptionId())
            .setMessage(message)
            .setChannel(channel != null ? channel : ServerEventsClient.UnknownChannel)
            .setSelector("cmd.chat"));
    }

    private void postRaw(ServerEventsClient client, String selector, String message) {
        postRaw(client, selector, message, null);
    }

    private void postRaw(ServerEventsClient client, String selector, String message, String channel) {
        client.getServiceClient().post(new PostRawToChannel()
            .setFrom(client.getSubscriptionId())
            .setMessage(message)
            .setChannel(channel != null ? channel : ServerEventsClient.UnknownChannel)
            .setSelector(selector));
    }

    private void postObject(ServerEventsClient client, CustomType dto){
        postObject(client, dto, null, null);
    }

    private void postObject(ServerEventsClient client, CustomType dto, String selector){
        postObject(client, dto, selector, null);
    }

    private void postObject(ServerEventsClient client, CustomType dto, String selector, String channel){
        client.getServiceClient().post(new PostObjectToChannel()
            .setCustomType(dto)
            .setChannel(channel != null ? channel : ServerEventsClient.UnknownChannel)
            .setSelector(selector));
    }

    private void postObject(ServerEventsClient client, SetterType dto){
        client.getServiceClient().post(new PostObjectToChannel()
            .setSetterType(dto)
            .setChannel(ServerEventsClient.UnknownChannel));
    }

    @Test
    public void Does_receive_messages() throws Exception {

        final List<ServerEventConnect> connectMsgs = new ArrayList<>();
        final List<ServerEventMessage> commands = new ArrayList<>();
        final List<ServerEventMessage> msgs1 = new ArrayList<>();
        final List<ServerEventMessage> msgs2 = new ArrayList<>();

        try (
                ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
                .setOnConnect(new ServerEventConnectCallback() {
                    @Override
                    public void execute(ServerEventConnect e) {
                        connectMsgs.add(e);
                    }
                })
                .setOnCommand(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        commands.add(e);
                    }
                })
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgs1.add(e);
                    }
                });

                ServerEventsClient client2 = createServerEventsClient("http://chat.servicestack.net")
                .setOnConnect(new ServerEventConnectCallback() {
                    @Override
                    public void execute(ServerEventConnect e) {
                        connectMsgs.add(e);
                    }
                })
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgs2.add(e);
                    }
                }))
        {
            client1.start();

            while (connectMsgs.size() < 1 || commands.size() < 1) {
                Thread.sleep(100);
            }

            client2.start();

            while (connectMsgs.size() < 2 || commands.size() < 2) {
                Thread.sleep(100);
            }

            ServerEventConnect info1 = connectMsgs.get(0);
            postChat(client1, "hello from client1");

            while (msgs1.size() < 1 || msgs2.size() < 1) {
                Thread.sleep(100);
            }

            ServerEventMessage msg1 = Func.first(msgs1);
            ServerEventMessage msg2 = Func.first(msgs2);

            assertTrue(msg1.getEventId() > 0);
            assertTrue(msg2.getEventId() > 0);
            assertEquals("cmd.chat", msg1.getSelector());
            assertEquals("cmd.chat", msg2.getSelector());

            ChatMessage chatMsg1 = JsonUtils.fromJson(msg1.getJson(), ChatMessage.class);
            ChatMessage chatMsg2 = JsonUtils.fromJson(msg2.getJson(), ChatMessage.class);

            for (ChatMessage chatMsg : new ChatMessage[]{ chatMsg1, chatMsg2 }){
                assertTrue(chatMsg.getId() > 0);
                assertEquals(info1.getUserId(), chatMsg.getFromUserId());
                assertEquals(info1.getDisplayName(), chatMsg.getFromName());
            }

            assertEquals(1, msgs1.size());
            assertEquals(1, msgs2.size());

            ServerEventConnect info2 = connectMsgs.get(1);
            postChat(client2, "hello from client2");

            while (msgs1.size() < 2 || msgs2.size() < 2) {
                Thread.sleep(100);
            }

            msg1 = msgs1.get(1);
            msg2 = msgs2.get(1);

            chatMsg1 = JsonUtils.fromJson(msg1.getJson(), ChatMessage.class);
            chatMsg2 = JsonUtils.fromJson(msg2.getJson(), ChatMessage.class);

            for (ChatMessage chatMsg : new ChatMessage[]{ chatMsg1, chatMsg2 }){
                assertTrue(chatMsg.getId() > 0);
                assertEquals(info2.getUserId(), chatMsg.getFromUserId());
                assertEquals(info2.getDisplayName(), chatMsg.getFromName());
            }

            assertEquals(2, msgs1.size());
            assertEquals(2, msgs2.size());
        }
    }

    @Test
    public void Does_send_multiple_heartbeats() throws Exception {
        final CountDownLatch signal = new CountDownLatch(1);

        final List<ServerEventMessage> heartbeats = new ArrayList<>();
        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
            .setOnConnect(new ServerEventConnectCallback() {
                @Override
                public void execute(ServerEventConnect e) {
                    e.setHeartbeatIntervalMs(1000);
                }
            }) //change to 1s
            .setOnHeartbeat(new ServerEventMessageCallback() {
                @Override
                public void execute(ServerEventMessage e) {
                    heartbeats.add(e);
                    if (heartbeats.size() >= 3)
                        signal.countDown();
                }
            })
            .start()) {

            assertTrue(signal.await(5, TimeUnit.SECONDS));

            assertTrue(heartbeats.size() >= 3);
        }
    }

    @Test
    public void Does_reconnect_on_lost_connection() throws Exception {

        final List<ServerEventConnect> connectMsgs = new ArrayList<>();
        final List<ServerEventMessage> msgs1 = new ArrayList<>();
        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
            .setOnConnect(new ServerEventConnectCallback() {
                @Override
                public void execute(ServerEventConnect e) {
                    connectMsgs.add(e);
                }
            })
            .setOnMessage(new ServerEventMessageCallback() {
                @Override
                public void execute(ServerEventMessage e) {
                    msgs1.add(e);
                }
            })
            .start()) {

            while (connectMsgs.size() < 1){
                Thread.sleep(100);
            }

            postChat(client1, "msg1 from client1");

            while (msgs1.size() < 1){
                Thread.sleep(100);
            }

            client1.getServiceClient().post(new ResetServerEvents());

            try(ServerEventsClient client2 = createServerEventsClient("http://chat.servicestack.net")
                    .setOnConnect(new ServerEventConnectCallback() {
                        @Override
                        public void execute(ServerEventConnect e) {
                            connectMsgs.add(e);
                        }
                    })
                    .start()) {

                while (connectMsgs.size() < 3){ //client1 + client1 reconnect + client2
                    Thread.sleep(100);
                }

                postChat(client2, "msg2 from client2");

                while (msgs1.size() < 2){ //msg1 + msg2
                    Thread.sleep(100);
                }
            }

            ServerEventMessage msg2 = msgs1.get(1);

            ChatMessage chatMsg2 = JsonUtils.fromJson(msg2.getJson(), ChatMessage.class);

            assertEquals("msg2 from client2", chatMsg2.getMessage());
        }
    }

    @Test
    public void Does_send_message_to_Handler() throws Exception {

        final List<ChatMessage> chatMsgs = new ArrayList<>();

        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
                .registerHandler("chat", new ServerEventCallback() {
                    @Override
                    public void execute(ServerEventsClient client, ServerEventMessage e) {
                        ChatMessage chatMsg = JsonUtils.fromJson(e.getJson(), ChatMessage.class);
                        chatMsgs.add(chatMsg);
                    }
                })
                .start()
                .waitTillConnected()) {

            postChat(client1, "msg1");

            while (chatMsgs.size() < 1){
                Thread.sleep(100);
            }

            ChatMessage chatMsg = Func.last(chatMsgs);
            assertEquals("msg1", chatMsg.getMessage());

            postChat(client1, "msg2");

            while (chatMsgs.size() < 2){
                Thread.sleep(100);
            }

            chatMsg = Func.last(chatMsgs);
            assertEquals("msg2", chatMsg.getMessage());
        }
    }

    @Test
    public void Does_send_message_to_named_receiver() throws Exception {
        final List<ServerEventMessage> msgs1 = new ArrayList<>();

        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
            .registerNamedReceiver("test", TestNamedReceiver.class)
            .setOnMessage(new ServerEventMessageCallback() {
                @Override
                public void execute(ServerEventMessage e) {
                    msgs1.add(e);
                }
            })
            .start()
            .waitTillConnected()) {

            postObject(client1, new CustomType()
                    .setId(1)
                    .setName("Foo"), "test.FooMethod");

            while (msgs1.size() < 1){
                Thread.sleep(100);
            }

            CustomType foo = TestNamedReceiver.FooMethodReceived;
            assertNotNull(foo);
            assertEquals(1, foo.getId().intValue());
            assertEquals("Foo", foo.getName());

            postObject(client1, new CustomType()
                    .setId(2)
                    .setName("Bar"), "test.BarMethod");

            while (msgs1.size() < 2){
                Thread.sleep(100);
            }

            CustomType bar = TestNamedReceiver.BarMethodReceived;
            assertNotNull(bar);
            assertEquals(2, bar.getId().intValue());
            assertEquals("Bar", bar.getName());

            postObject(client1, new CustomType()
                    .setId(3)
                    .setName("Baz"), "test.BazMethod");

            while (msgs1.size() < 3){
                Thread.sleep(100);
            }

            CustomType baz = TestNamedReceiver.NoSuchMethodReceived;
            assertNotNull(baz);
            assertEquals(3, baz.getId().intValue());
            assertEquals("Baz", baz.getName());
            assertEquals("BazMethod", TestNamedReceiver.NoSuchMethodSelector);

            postObject(client1, new CustomType()
                    .setId(4)
                    .setName("Qux"), "test.QuxSetter");

            while (msgs1.size() < 4){
                Thread.sleep(100);
            }

            CustomType qux = TestNamedReceiver.QuxSetterReceived;
            assertNotNull(qux);
            assertEquals(4, qux.getId().intValue());
            assertEquals("Qux", qux.getName());
        }
    }

    @Test
    public void Does_send_message_to_global_receiver() throws Exception {
        final List<ServerEventMessage> msgs1 = new ArrayList<>();

        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
                .registerReceiver(TestGlobalReceiver.class)
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgs1.add(e);
                    }
                })
                .start()
                .waitTillConnected()) {

            postObject(client1, new CustomType()
                    .setId(1)
                    .setName("Foo"));

            while (msgs1.size() < 1){
                Thread.sleep(100);
            }

            CustomType foo = TestGlobalReceiver.CustomTypeReceived;
            assertNotNull(foo);
            assertEquals(1, foo.getId().intValue());
            assertEquals("Foo", foo.getName());
        }
    }

    @Test
    public void Does_set_properties_on_global_receiver() throws Exception {
        final List<ServerEventMessage> msgs1 = new ArrayList<>();

        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
                .registerReceiver(TestGlobalReceiver.class)
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgs1.add(e);
                    }
                })
                .start()
                .waitTillConnected()) {

            postObject(client1, new SetterType()
                    .setId(1)
                    .setName("Foo"));

            while (msgs1.size() < 1){
                Thread.sleep(100);
            }

            SetterType foo = TestGlobalReceiver.SetterTypeReceived;
            assertNotNull(foo);
            assertEquals(1, foo.getId().intValue());
            assertEquals("Foo", foo.getName());
        }
    }

    @Test
    public void Does_send_raw_string_messages() throws Exception {
        final List<ServerEventMessage> msgs1 = new ArrayList<>();

        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
            .registerReceiver(TestJavaScriptReceiver.class)
            .registerNamedReceiver("css", TestJavaScriptReceiver.class)
            .setOnMessage(new ServerEventMessageCallback() {
                @Override
                public void execute(ServerEventMessage e) {
                    msgs1.add(e);
                }
            })
            .start()
            .waitTillConnected()) {

            postChat(client1, "chat msg");

            while (msgs1.size() < 1) {
                Thread.sleep(100);
            }

            ChatMessage chatMsg = TestJavaScriptReceiver.ChatReceived;
            assertNotNull(chatMsg);
            assertEquals("chat msg", chatMsg.getMessage());

            postRaw(client1, "cmd.announce", "This is your captain speaking...");

            while (msgs1.size() < 2) {
                Thread.sleep(100);
            }

            String announce = TestJavaScriptReceiver.AnnounceReceived;
            assertEquals("This is your captain speaking...", announce);

            postRaw(client1, "cmd.toggle$#channels", null);

            while (msgs1.size() < 3) {
                Thread.sleep(100);
            }

            String toggle = TestJavaScriptReceiver.ToggleReceived;
            assertEquals("", toggle);
            ServerEventMessage toggleRequest = TestJavaScriptReceiver.ToggleRequestReceived;
            assertEquals("cmd.toggle$#channels", toggleRequest.getSelector());
            assertEquals("cmd", toggleRequest.getOp());
            assertEquals("toggle", toggleRequest.getTarget());
            assertEquals("#channels", toggleRequest.getCssSelector());

            postRaw(client1, "css.background-image$#top", "url(http://bit.ly/1yIJOBH)");

            while (msgs1.size() < 4) {
                Thread.sleep(100);
            }

            String bgImage = TestJavaScriptReceiver.BackgroundImageReceived;
            assertEquals("url(http://bit.ly/1yIJOBH)", bgImage);
            ServerEventMessage bgImageRequest = TestJavaScriptReceiver.BackgroundImageRequestReceived;
            assertEquals("css.background-image$#top", bgImageRequest.getSelector());
            assertEquals("css", bgImageRequest.getOp());
            assertEquals("background-image", bgImageRequest.getTarget());
            assertEquals("#top", bgImageRequest.getCssSelector());
        }
    }

    @Test
    public void Can_reuse_same_instance() throws Exception {
        final List<ServerEventMessage> msgs1 = new ArrayList<>();

        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
                .registerReceiver(TestJavaScriptReceiver.class)
                .registerNamedReceiver("css", TestJavaScriptReceiver.class)
                .setResolver(new SingletonInstanceResolver())
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgs1.add(e);
                    }
                })
                .start()
                .waitTillConnected()) {

            postRaw(client1, "cmd.announce", "This is your captain speaking...");

            while (msgs1.size() < 1) {
                Thread.sleep(100);
            }

            TestJavaScriptReceiver instance = (TestJavaScriptReceiver)client1.getResolver().TryResolve(TestJavaScriptReceiver.class);
            assertEquals("This is your captain speaking...", instance.AnnounceInstance);

            postRaw(client1, "cmd.announce", "2nd Announcement");

            while (msgs1.size() < 2) {
                Thread.sleep(100);
            }

            assertEquals("2nd Announcement", instance.AnnounceInstance);
        }
    }

    public void Can_use_IOC_to_autowire_Receivers(){
        //No built-in IOC
    }

    @Test
    public void Does_receive_messages_on_to_clients_subscribed_on_multiple_channels() throws Exception {

        final List<ServerEventMessage> msgsA = new ArrayList<>();
        final List<ServerEventMessage> msgsAB = new ArrayList<>();
        final List<ServerEventMessage> msgsABC = new ArrayList<>();
        final List<ServerEventMessage> msgsABCD = new ArrayList<>();

        try(ServerEventsClient clientA = createServerEventsClient("http://chat.servicestack.net", "A")
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgsA.add(e);
                    }
                })
                .start();
            ServerEventsClient clientAB = createServerEventsClient("http://chat.servicestack.net", "A", "B")
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgsAB.add(e);
                    }
                })
                .start();
            ServerEventsClient clientABC = createServerEventsClient("http://chat.servicestack.net", "A", "B", "C")
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgsABC.add(e);
                    }
                })
                .start();
            ServerEventsClient clientABCD = createServerEventsClient("http://chat.servicestack.net", "A", "B", "C", "D")
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgsABCD.add(e);
                    }
                })
                .start()) {

            clientA.waitTillConnected();
            clientAB.waitTillConnected();
            clientABC.waitTillConnected();
            clientABCD.waitTillConnected();

            List<ServerEventUser> channelAsubscribers = clientA.getChannelSubscribers();
            assertEquals(4, channelAsubscribers.size());

            List<ServerEventUser> channelABsubscribers = clientAB.getChannelSubscribers();
            assertEquals(4, channelABsubscribers.size());

            System.out.println("Publishing Msg Batch #1 ...");
            postChat(clientA, "#1 hello to A", "A");
            postChat(clientA, "#2 hello to B", "B");
            postChat(clientA, "#3 hello to C", "C");
            postChat(clientA, "#4 hello to D", "D");

            Thread.sleep(1000);

            assertEquals(1, msgsA.size());
            assertEquals(2, msgsAB.size());
            assertEquals(3, msgsABC.size());
            assertEquals(4, msgsABCD.size());

            System.out.println("Publishing Msg Batch #2 ...");
            postChat(clientA, "#5 hello to A", "A");
            postChat(clientA, "#6 hello to B", "B");
            postChat(clientA, "#7 hello to C", "C");
            postChat(clientA, "#8 hello to D", "D");

            Thread.sleep(1000);

            assertEquals(2, msgsA.size());
            assertEquals(4, msgsAB.size());
            assertEquals(6, msgsABC.size());
            assertEquals(8, msgsABCD.size());
        }
    }

    @Test
    public void Does_receive_all_join_and_leave_messages() throws Exception {
        final List<ServerEventJoin> joinA = new ArrayList<>();
        final List<ServerEventJoin> joinB = new ArrayList<>();
        final List<ServerEventJoin> joinAB = new ArrayList<>();

        final List<ServerEventLeave> leaveA = new ArrayList<>();
        final List<ServerEventLeave> leaveB = new ArrayList<>();
        final List<ServerEventLeave> leaveAB = new ArrayList<>();

        try(ServerEventsClient clientA = createServerEventsClient("http://chat.servicestack.net", "A")
                .setOnCommand(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        if (e instanceof ServerEventJoin) {
                            joinA.add((ServerEventJoin) e);
                        } else if (e instanceof ServerEventLeave) {
                            leaveA.add((ServerEventLeave) e);
                        }
                    }
                });
            ServerEventsClient clientB = createServerEventsClient("http://chat.servicestack.net", "B")
                .setOnCommand(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        if (e instanceof ServerEventJoin) {
                            joinB.add((ServerEventJoin) e);
                        } else if (e instanceof ServerEventLeave) {
                            leaveB.add((ServerEventLeave) e);
                        }
                    }
                });
            ServerEventsClient clientAB = createServerEventsClient("http://chat.servicestack.net", "A", "B")
                .setOnCommand(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        if (e instanceof ServerEventJoin) {
                            joinAB.add((ServerEventJoin) e);
                        } else if (e instanceof ServerEventLeave) {
                            leaveAB.add((ServerEventLeave) e);
                        }
                    }
                });
        ){

            clientA.start().waitTillConnected();
            clientB.start().waitTillConnected();
            clientAB.start().waitTillConnected();

            while (joinA.size() < 2 || joinB.size() < 2 || joinAB.size() < 2){
                Thread.sleep(100);
            }

            assertEquals(2, joinA.size());  //A + [(A) B]
            assertEquals(2, joinB.size());  //B + [A (B)]
            assertEquals(2, joinAB.size()); //[(A) B] + [A (B)]

            List<ServerEventUser> channelAsubscribers = clientA.getChannelSubscribers();
            assertEquals(2, channelAsubscribers.size());

            List<ServerEventUser> channelBsubscribers = clientB.getChannelSubscribers();
            assertEquals(2, channelBsubscribers.size());

            List<ServerEventUser> channelABsubscribers = clientAB.getChannelSubscribers();
            assertEquals(3, channelABsubscribers.size());

            ArrayList<HashMap<String,String>> usersA = clientA.getServiceClient().get(new GetEventSubscribers()
                    .setChannels(Func.toList("A")));
            ArrayList<HashMap<String,String>> usersB = clientA.getServiceClient().get(new GetEventSubscribers()
                    .setChannels(Func.toList("B")));
            ArrayList<HashMap<String,String>> usersAB = clientA.getServiceClient().get(new GetEventSubscribers()
                    .setChannels(Func.toList("A", "B")));

            assertEquals(2, usersA.size());
            assertEquals(2, usersB.size());
            assertEquals(3, usersAB.size());

            clientAB.stop();

            Thread.sleep(100);

            clientB.stop();
            clientA.stop();

            Thread.sleep(100);

            assertEquals(1, leaveA.size());
            assertEquals(1, leaveB.size());
            assertEquals(0, leaveAB.size());
        }
    }

    @Test
    public void MultiChannel_Does_receive_all_join_and_leave_messages() throws Exception {
        final List<ServerEventJoin> joinA = new ArrayList<>();
        final List<ServerEventJoin> joinB = new ArrayList<>();
        final List<ServerEventJoin> joinAB = new ArrayList<>();

        final List<ServerEventLeave> leaveA = new ArrayList<>();
        final List<ServerEventLeave> leaveB = new ArrayList<>();
        final List<ServerEventLeave> leaveAB = new ArrayList<>();

        try(ServerEventsClient clientAB = createServerEventsClient("http://chat.servicestack.net", "A", "B")
                .setOnCommand(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        if (e instanceof ServerEventJoin) {
                            joinAB.add((ServerEventJoin) e);
                        } else if (e instanceof ServerEventLeave) {
                            leaveAB.add((ServerEventLeave) e);
                        }
                    }
                });
            ServerEventsClient clientA = createServerEventsClient("http://chat.servicestack.net", "A")
                .setOnCommand(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        if (e instanceof ServerEventJoin) {
                            joinA.add((ServerEventJoin) e);
                        } else if (e instanceof ServerEventLeave) {
                            leaveA.add((ServerEventLeave) e);
                        }
                    }
                });
            ServerEventsClient clientB = createServerEventsClient("http://chat.servicestack.net", "B")
                .setOnCommand(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        if (e instanceof ServerEventJoin) {
                            joinB.add((ServerEventJoin) e);
                        } else if (e instanceof ServerEventLeave) {
                            leaveB.add((ServerEventLeave) e);
                        }
                    }
                });
        ){

            clientAB.start().waitTillConnected();
            clientA.start().waitTillConnected();
            clientB.start().waitTillConnected();

            while (joinAB.size() < 4 //[(A) B] + [A (B)] + A + B
                    || joinA.size() < 1 || joinB.size() < 1) {
                Thread.sleep(100);
            }

            clientA.stop();
            clientB.stop();

            Thread.sleep(100);

            clientAB.stop();

            assertEquals(2, leaveAB.size());
            assertEquals(0, leaveA.size());
            assertEquals(0, leaveB.size());
        }
    }

    @Test
    public void Can_subscribe_to_channels_whilst_connected() throws Exception {
        final List<ServerEventMessage> msgs1 = new ArrayList<>();
        final List<ServerEventMessage> msgs2 = new ArrayList<>();

        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net", "A")
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgs1.add(e);
                    }
                })
                .start();
            ServerEventsClient client2 = createServerEventsClient("http://chat.servicestack.net", "B")
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgs2.add(e);
                    }
                })
                .start()) {

            client1.waitTillConnected();
            client2.waitTillConnected();

            assertArrayEquals(new String[]{"A"}, client1.getChannels());

            postChat(client2, "#1 hello to B", "B");

            Thread.sleep(500);

            assertEquals(0, msgs1.size());
            assertEquals(1, msgs2.size());

            client1.subscribeToChannels("B");

            Thread.sleep(500);

            postChat(client2, "#2 hello to B", "B");
            postChat(client2, "#3 hello to C", "C");
            Thread.sleep(500);

            assertArrayEquals(new String[]{"A", "B"}, client1.getChannels());
            assertArrayEquals(new String[]{"B"}, client2.getChannels());

            assertTrue(client1.getEventStreamUri().endsWith("?channels=A,B"));
            assertTrue(client2.getEventStreamUri().endsWith("?channels=B"));

            client1.subscribeToChannels("C");
            client2.subscribeToChannels("C");
            Thread.sleep(500);

            postChat(client2, "#4 hello to C", "C");
            Thread.sleep(500);

            assertArrayEquals(new String[]{"A", "B", "C"}, client1.getChannels());
            assertArrayEquals(new String[]{"B", "C"}, client2.getChannels());

            assertTrue(client1.getEventStreamUri().endsWith("?channels=A,B,C"));
            assertTrue(client2.getEventStreamUri().endsWith("?channels=B,C"));
        }
    }

    @Test
    public void Can_unsubscribe_from_channels_whilst_connected() throws Exception {
        final List<ServerEventMessage> msgs1 = new ArrayList<>();
        final List<ServerEventMessage> msgs2 = new ArrayList<>();

        try(ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net", "A","B","C")
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgs1.add(e);
                    }
                })
                .start()
                .waitTillConnected();
            ServerEventsClient client2 = createServerEventsClient("http://chat.servicestack.net", "B","C")
                .setOnMessage(new ServerEventMessageCallback() {
                    @Override
                    public void execute(ServerEventMessage e) {
                        msgs2.add(e);
                    }
                })
                .start()
                .waitTillConnected()) {

            assertArrayEquals(new String[]{"A","B","C"}, client1.getChannels());

            postChat(client2, "#1 hello to B", "B");
            Thread.sleep(500);

            assertEquals(1, msgs1.size());
            assertEquals(1, msgs2.size());

            client1.unSubscribeFromChannels("B");
            Thread.sleep(500);

            postChat(client2, "#2 hello to B", "B");
            postChat(client2, "#3 hello to C", "C");
            Thread.sleep(500);

            assertEquals(2, msgs1.size());
            assertEquals(3, msgs2.size());

            assertArrayEquals(new String[]{"A", "C"}, client1.getChannels());
            assertArrayEquals(new String[]{"B", "C"}, client2.getChannels());

            assertTrue(client1.getEventStreamUri().endsWith("?channels=A,C"));
            assertTrue(client2.getEventStreamUri().endsWith("?channels=B,C"));

            client1.unSubscribeFromChannels("C");
            client2.unSubscribeFromChannels("C");
            Thread.sleep(500);

            postChat(client2, "#4 hello to C", "C");
            Thread.sleep(500);

            assertArrayEquals(new String[]{"A"}, client1.getChannels());
            assertArrayEquals(new String[]{"B"}, client2.getChannels());

            assertTrue(client1.getEventStreamUri().endsWith("?channels=A"));
            assertTrue(client2.getEventStreamUri().endsWith("?channels=B"));
        }
    }

    @Test
    public void test_Does_fire_multiple_listeners_for_custom_trigger() throws Exception {
        final List<ServerEventMessage> msgs1 = new ArrayList<>();
        final List<ServerEventMessage> msgs2 = new ArrayList<>();

        Action<ServerEventMessage> handler = new Action<ServerEventMessage>() {
            @Override
            public void apply(ServerEventMessage e) {
                msgs1.add(e);
            }
        };

        try (ServerEventsClient client1 = createServerEventsClient("http://chat.servicestack.net")
                .addListener("customEvent", handler)
                .addListener("customEvent", new Action<ServerEventMessage>() {
                    @Override
                    public void apply(ServerEventMessage e) {
                        msgs2.add(e);
                    }
                })
                .start()
                .waitTillConnected();
             ServerEventsClient client2 = createServerEventsClient("http://chat.servicestack.net")
                     .start()
                     .waitTillConnected()) {

            postRaw(client2, "trigger.customEvent", "arg");

            while (msgs1.size() < 1 || msgs2.size() < 1) {
                Thread.sleep(100);
            }

            assertEquals(1, msgs1.size());
            assertEquals(1, msgs2.size());

            client1.removeListener("customEvent", handler);

            postRaw(client2, "trigger.customEvent", "arg");

            while (msgs1.size() < 1 || msgs2.size() < 2) {
                Thread.sleep(100);
            }

            assertEquals(1, msgs1.size());
            assertEquals(2, msgs2.size());

            assertTrue(Func.all(Func.concat(msgs1, msgs2), new Predicate<ServerEventMessage>() {
                @Override
                public boolean apply(ServerEventMessage msg) {
                    return "arg".equals(JsonUtils.fromJson(msg.getJson(), String.class));
                }
            }));
        }
    }}
