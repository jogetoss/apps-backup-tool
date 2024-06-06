package org.joget.marketplace;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joget.apps.app.dao.AppDefinitionDao;
import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.CustomBuilder;
import org.joget.apps.app.model.CustomBuilderCallback;
import org.joget.apps.app.model.PackageDefinition;
import org.joget.apps.app.service.AppDevUtil;
import org.joget.apps.app.service.AppResourceUtil;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.app.service.CustomBuilderUtil;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.LogUtil;
import org.joget.directory.model.Group;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.base.PluginWebSupport;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONArray;

public class AppBackupTool extends DefaultApplicationPlugin implements PluginWebSupport {

    private final static String MESSAGE_PATH = "messages/AppBackupTool";
    public static final String APP_ZIP_PREFIX = "APP_";
    private static final String ALL_APPS = "ALL";
    private static final String SELECTED_APPS = "SELECTED";

    @Override
    public Object execute(Map map) {
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        String toBackup = getPropertyString("toBackup");
        String backupPath = getPropertyString("backupPath");
        String appData = getPropertyString("appData");
        String appPlugins = getPropertyString("appPlugins");
        String appGroups = getPropertyString("appGroups");
        String unpublished = getPropertyString("unpublished");

        File backupDir = new File(backupPath);
        if (!backupDir.exists() || !backupDir.isDirectory()) {
            LogUtil.info(getClassName(), "Backup path does not exist or is not a directory: " + backupPath);
            return null;
        }

        if (ALL_APPS.equalsIgnoreCase(toBackup)) {
            Collection<AppDefinition> publishedAppList = appService.getPublishedApps(null);
            Collection<AppDefinition> combinedAppList = new ArrayList<>(publishedAppList);
            if ("true".equalsIgnoreCase(unpublished)) {
                Collection<AppDefinition> unpublishedAppList = getUnpublishedApps();
                combinedAppList.addAll(unpublishedAppList);
            }
            for (AppDefinition ad : combinedAppList) {
                String appId = ad.getAppId();
                AppDefinition appDef = appService.getAppDefinition(appId, String.valueOf(ad.getVersion()));
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
                String timestamp = sdf.format(new Date());
                String filename = APP_ZIP_PREFIX + appDef.getId() + "-" + appDef.getVersion() + "-" + timestamp + ".jwa";
                if (!backupPath.endsWith(File.separator)) {
                    backupPath += File.separator;
                }
                File outputFile = new File(backupPath, filename);
                try ( OutputStream outputStream = new FileOutputStream(outputFile)) {
                    exportApp(appId, appId, outputStream, appData, appPlugins, appGroups);
                } catch (IOException ex) {
                    LogUtil.error(getClassName(), ex, ex.getMessage());
                }
            }
        } else if (SELECTED_APPS.equalsIgnoreCase(toBackup)) {
            String selectedApp = getPropertyString("selectedApp");
            if (selectedApp != null && !selectedApp.isEmpty()) {
                String[] pieces = selectedApp.split(";");
                for (String appId : pieces) {
                    AppDefinition ad = appService.getPublishedAppDefinition(appId);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
                    String timestamp = sdf.format(new Date());
                    String filename = APP_ZIP_PREFIX + ad.getId() + "-" + ad.getVersion() + "-" + timestamp + ".jwa";
                    if (!backupPath.endsWith(File.separator)) {
                        backupPath += File.separator;
                    }
                    File outputFile = new File(backupPath, filename);
                    try ( OutputStream outputStream = new FileOutputStream(outputFile)) {
                        exportApp(appId, appId, outputStream, appData, appPlugins, appGroups);
                    } catch (IOException ex) {
                        LogUtil.error(getClassName(), ex, ex.getMessage());
                    }
                }
            }
        }
        return null;
    }

    public Collection<AppDefinition> getUnpublishedApps() {
        AppDefinitionDao appDefinitionDao = (AppDefinitionDao) FormUtil.getApplicationContext().getBean("appDefinitionDao");
        Collection<AppDefinition> publishedList = appDefinitionDao.findPublishedApps("name", Boolean.FALSE, null, null);
        Collection<String> publishedIdSet = new HashSet<String>();
        for (AppDefinition appDef : publishedList) {
            publishedIdSet.add(appDef.getAppId());
        }
        Collection<AppDefinition> unpublishedList = new ArrayList<AppDefinition>();
        Collection<AppDefinition> appDefinitionList = appDefinitionDao.findLatestVersions(null, null, null, "name", Boolean.FALSE, null, null);
        for (Iterator<AppDefinition> i = appDefinitionList.iterator(); i.hasNext();) {
            AppDefinition appDef = i.next();
            if (!publishedIdSet.contains(appDef.getAppId())) {
                unpublishedList.add(appDef);
            }
        }
        return unpublishedList;
    }

    public OutputStream exportApp(String appId, String version, OutputStream output, String appData, String appPlugins, String appGroups) throws IOException {
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        WorkflowManager workflowManager = (WorkflowManager) AppUtil.getApplicationContext().getBean("workflowManager");
        FormDefinitionDao formDefinitionDao = (FormDefinitionDao) FormUtil.getApplicationContext().getBean("formDefinitionDao");

        ZipOutputStream zip = null;
        if (output == null) {
            output = new ByteArrayOutputStream();
        }
        try {
            AppDefinition appDef = appService.loadAppDefinition(appId, version);
            if (appDef != null && output != null) {
                zip = new ZipOutputStream(output);

                // write zip entry for app XML
                byte[] data = appService.getAppDefinitionXml(appId, appDef.getVersion());
                zip.putNextEntry(new ZipEntry("appDefinition.xml"));
                zip.write(data);
                zip.closeEntry();

                // write zip entry for app XML
                PackageDefinition packageDef = appDef.getPackageDefinition();
                if (packageDef != null) {
                    byte[] xpdl = workflowManager.getPackageContent(packageDef.getId(), packageDef.getVersion().toString());
                    zip.putNextEntry(new ZipEntry("package.xpdl"));
                    zip.write(xpdl);
                    zip.closeEntry();
                }

                AppResourceUtil.addResourcesToZip(appId, version, zip);

                if ("true".equalsIgnoreCase(appPlugins)) {
                    AppDevUtil.addPluginsToZip(appDef, zip);
                }

                if ("true".equalsIgnoreCase(appData)) {
                    Collection<String> tableNameList = formDefinitionDao.getTableNameList(appDef);
                    String[] tableNameArray = tableNameList.toArray(new String[0]);
                    appService.exportFormData(appId, version, zip, tableNameArray);
                }

                if ("true".equalsIgnoreCase(appGroups)) {
                    Collection<Group> userGroups = appService.getAppUserGroups(appDef);
                    String[] groupArray = new String[userGroups.size()];
                    int index = 0;
                    for (Group group : userGroups) {
                        groupArray[index++] = group.getId();
                    }
                    appService.exportUserGroups(appId, version, zip, groupArray);
                }

                for (CustomBuilder builder : CustomBuilderUtil.getBuilderList().values()) {
                    if (builder instanceof CustomBuilderCallback) {
                        ((CustomBuilderCallback) builder).exportAppPostProcessing(appDef, zip);
                    }
                }

                // finish the zip
                zip.finish();
            }
        } catch (Exception ex) {
            LogUtil.error(getClass().getName(), ex, "");
        } finally {
            if (zip != null) {
                zip.flush();
            }
        }
        return output;
    }

    @Override
    public String getName() {
        return "Apps Backup Tool";
    }

    @Override
    public String getVersion() {
        return "8.0.0";
    }

    @Override
    public String getDescription() {
        return "To backup all the apps and store them at provided path";
    }

    @Override
    public String getLabel() {
        return "Apps Backup Tool";
    }

    @Override
    public String getClassName() {
        return this.getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClass().getName(), "/properties/appBackupTool.json", null, true, MESSAGE_PATH);
    }

    @Override
    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        boolean isAdmin = WorkflowUtil.isCurrentUserInRole(WorkflowUserManager.ROLE_ADMIN);
        if (!isAdmin) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        Collection<AppDefinition> publishedAppList = appService.getPublishedApps(null);
        Collection<AppDefinition> unpublishedAppList = getUnpublishedApps();

        JSONArray jsonArray = new JSONArray();
        Map blank = new HashMap();
        blank.put("value", "");
        blank.put("label", "");
        jsonArray.put(blank);
        for (AppDefinition appDef : publishedAppList) {
            Map data = new HashMap();
            data.put("value", appDef.getId());
            data.put("label", appDef.getName());
            jsonArray.put(data);
        }

        for (AppDefinition appDef : unpublishedAppList) {
            Map data = new HashMap();
            data.put("value", appDef.getId());
            data.put("label", appDef.getName());
            jsonArray.put(data);
        }

        PrintWriter writer = response.getWriter();
        AppUtil.writeJson(writer, jsonArray, null);
    }

}
