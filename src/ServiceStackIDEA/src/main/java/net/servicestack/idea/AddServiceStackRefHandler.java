package net.servicestack.idea;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiPackage;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class AddServiceStackRefHandler {

    private static final String dependencyGroupId = "net.servicestack";
    private static final String dependencyPackageId = "android";
    //Fallback version of dependencies if GitHub tags can't be checked.
    private static String dependencyVersion = "1.0.15";
    private static final String clientPackageId = "client";
    private static INativeTypesHandler defaultNativeTypesHandler;

    public static void setDependencyVersion(String version) {
        dependencyVersion = version;
    }


    public static void handleOk(String addressUrl, String qualifiedPackageName,
                                String fileName, String selectedDirectory,
                                Module module, StringBuilder errorMessage) {
        List<String> javaCodeLines = getDtoLines(addressUrl, qualifiedPackageName, fileName, errorMessage);
        if (javaCodeLines == null) return;
        defaultNativeTypesHandler = IDEAUtils.getDefaultNativeTypesHandler(module);
        boolean showDto = true;
        boolean isMavenModule = IDEAPomFileHelper.isMavenModule(module);
        if(isMavenModule) {
            showDto = !tryAddMavenDependency(module);
        } else {
            //Gradle
            try {
                showDto = !addGradleDependencyIfRequired(module);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                String message = "Failed to update build.gradle with '" +
                        dependencyGroupId + ":" + clientPackageId + ":" + dependencyVersion +
                        "'. " + e.getLocalizedMessage();
                Notification notification = new Notification(
                        "ServiceStackIDEA",
                        "Warning Add ServiceStack Reference",
                        message,
                        NotificationType.WARNING);
                Notifications.Bus.notify(notification);
            }
        }

        String dtoPath;
        try {
            dtoPath = getDtoPath(module,qualifiedPackageName,selectedDirectory, fileName,errorMessage);
        } catch (Exception e) {
            return;
        }

        if (!IDEAUtils.writeDtoFile(javaCodeLines, dtoPath, errorMessage)) {
            return;
        }
        Analytics.SubmitAnonymousAddReferenceUsage(getNativeTypesHandler(fileName));
        IDEAUtils.refreshFile(module, dtoPath, showDto);
        VirtualFileManager.getInstance().syncRefresh();
    }

    @Nullable
    private static List<String> getDtoLines(String addressUrl, String qualifiedPackageName, String fileName, StringBuilder errorMessage) {
        Map<String,String> options = new HashMap<>();
        List<String> javaCodeLines;
        try {
            options.put("Package", qualifiedPackageName);
            String name = getDtoNameWithoutExtension(fileName).replaceAll("\\.", "_");
            options.put("GlobalNamespace", name);
            javaCodeLines = getNativeTypesHandler(fileName).getUpdatedCode(addressUrl, options);

            if (!javaCodeLines.get(0).startsWith("/* Options:")) {
                //Invalid endpoint
                errorMessage.append("The address url is not a valid ServiceStack endpoint.");
                return null;
            }
        }
        catch (URISyntaxException e) {
            e.printStackTrace();
            DialogErrorMessages.appendInvalidEnpoint(errorMessage, addressUrl, e);
            return null;
        } catch (MalformedURLException e) {
            e.printStackTrace();
            DialogErrorMessages.appendInvalidEnpoint(errorMessage, addressUrl, e);
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            DialogErrorMessages.appendReadResponseError(errorMessage, addressUrl, e);
            return null;
        }
        return javaCodeLines;
    }

    private static INativeTypesHandler getNativeTypesHandler(String fileName) {
        INativeTypesHandler nativeTypesHandler = IDEAUtils.getNativeTypesHandler(fileName);
        if(nativeTypesHandler != null) {
            return nativeTypesHandler;
        }
        return defaultNativeTypesHandler;
    }

    private static boolean tryAddMavenDependency(Module module) {
        boolean showDto;
        String message = "Unable to locate module pom.xml file. Can't add required dependency '" +
                dependencyGroupId + ":" + clientPackageId + ":" + dependencyVersion +
                "'.";
        Notification notification = new Notification(
                "ServiceStackIDEA",
                "Warning Add ServiceStack Reference",
                message,
                NotificationType.WARNING);
        try {
            IDEAPomFileHelper pomFileHelper = new IDEAPomFileHelper();
            String pomFilePath = IDEAPomFileHelper.findNearestModulePomFile(module);
            if(pomFilePath == null) {
                Notifications.Bus.notify(notification);
                return false;
            }
            File pomLibFile = new File(pomFilePath);
            showDto = pomFileHelper.addMavenDependency(module,pomLibFile, dependencyGroupId, clientPackageId, dependencyVersion);
            IDEAUtils.refreshFile(module,pomFilePath,showDto);
        } catch(Exception e) {
            showDto = false;
            e.printStackTrace();
            notification = new Notification(
                    "ServiceStackIDEA",
                    "Warning Add ServiceStack Reference",
                    "Unable to add maven dependency. " + e.getLocalizedMessage(),
                    NotificationType.WARNING);
            Notifications.Bus.notify(notification);
        }
        return showDto;
    }

    private static boolean addGradleDependencyIfRequired(Module module) throws FileNotFoundException {
        boolean result = false;
        if(GradleBuildFileHelper.addDependency(module,dependencyGroupId, dependencyPackageId, dependencyVersion)) {
            result = true;
            IDEAUtils.refreshBuildFile(module);
        }
        return result;
    }

    private static String getDtoPath(Module module, String qualifiedPackageName, String selectedDirectory, String fileName, StringBuilder errorMessage) throws FileNotFoundException {
        VirtualFile moduleFile = module.getModuleFile();
        if(moduleFile == null) {
            throw new FileNotFoundException("Module file not found. Unable to add DTO to project.");
        }
        String fullDtoPath;

        PsiPackage mainPackage = JavaPsiFacade.getInstance(module.getProject()).findPackage(qualifiedPackageName);
        if(mainPackage != null && mainPackage.isValid() && mainPackage.getDirectories().length > 0) {
            File file = new File(selectedDirectory);
            VirtualFile selectedFolder = LocalFileSystem.getInstance().findFileByIoFile(file);
            if(selectedFolder == null) {
                errorMessage.append("Unable to determine path for DTO file.");
                throw new FileNotFoundException();
            }
            PsiDirectory rootPackageDir = PsiManager.getInstance(module.getProject()).findDirectory(selectedFolder);
            if(rootPackageDir == null) {
                errorMessage.append("Unable to determine path for DTO file.");
                throw new FileNotFoundException();
            }
            fullDtoPath = rootPackageDir.getVirtualFile().getPath() +
                    File.separator +
                    getDtoFileName(fileName);
        } else {
            String moduleSourcePath;
            if(moduleFile.getParent() == null) {
                moduleSourcePath = moduleFile.getPath() + "/main/java";
            } else {
                moduleSourcePath = moduleFile.getParent().getPath() + "/src/main/java";
            }
            fullDtoPath = moduleSourcePath + File.separator + getDtoFileName(fileName);
        }
        return fullDtoPath;
    }

    public static String getDtoFileName(String name) {
        INativeTypesHandler nativeTypesHandler = getNativeTypesHandler(name);
        int p = name.lastIndexOf(".");
        String e = name.substring(p);
        if (p == -1 || !e.equals(nativeTypesHandler.getFileExtension())) {
            /* file has no extension */
            return name + nativeTypesHandler.getFileExtension();
        } else {
            /* file has extension e */
            return name;
        }
    }

    public static String getDtoNameWithoutExtension(String name) {
        INativeTypesHandler nativeTypesHandler = getNativeTypesHandler(name);
        int p = name.lastIndexOf(".");
        String e = name.substring(p);
        if (p == -1 || !e.equals(nativeTypesHandler.getFileExtension())) {
            /* file has no extension */
            return name;
        } else {
            /* file has extension e */
            return name.substring(0, p);
        }
    }
}
