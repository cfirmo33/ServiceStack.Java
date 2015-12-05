package test.servicestack.net.kotlinapp

import android.app.Application
import android.test.ApplicationTestCase
import com.google.gson.Gson
import junit.framework.Assert
import net.servicestack.android.AndroidLogProvider
import net.servicestack.client.Log
import test.servicestack.net.kotlin.techstacks.Technology
import test.servicestack.net.kotlin.techstacks.TechnologyTier

/**
 * [Testing Fundamentals](http://d.android.com/tools/testing/testing_android.html)
 */
class ApplicationTest : ApplicationTestCase<Application>(Application::class.java) {

    override fun setUp() {
        super.setUp()
        Log.Instance = AndroidLogProvider("ZZZ");
    }

    fun test_Fail() {
        Assert.assertTrue(true)
    }

    fun test_Can_Serialize_Technology(){
        val dto = Technology();
        dto.Name = "Test";
        dto.Tier = TechnologyTier.ProgrammingLanguage;

        var gson = Gson()

        val json = gson.toJson(dto);

        Log.d("JSON: $json")

        val fromJson = gson.fromJson(json, Technology::class.java)

        Log.d("Name: ${fromJson.Name}, Tier: ${fromJson.Tier}")
    }
}