/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;
import org.json.JSONObject;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import io.goobi.workflow.api.connection.HttpUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;

@PluginImplementation
@Log4j2
public class DownloadAndVerifyAssetsStepPlugin implements IStepPluginVersion2 {
    private static final long serialVersionUID = 1L;

    @Getter
    private String title = "intranda_step_download_and_verify_assets";
    @Getter
    private Step step;

    private Process process;
    private transient VariableReplacer replacer;

    private String returnPath;
    private List<String> errorsList = new ArrayList<>();

    // <fileNameProperty>
    private transient List<FileNameProperty> fileNameProperties = new ArrayList<>();
    // <response type="success">
    private transient List<SingleResponse> successResponses = new ArrayList<>();
    // <response type="error">
    private transient List<SingleResponse> errorResponses = new ArrayList<>();
    // how many times shall be maximally tried before reporting final results
    private int maxTryTimes;
    // @urlProperty -> @hashProperty
    private Map<String, String> urlHashMap = new HashMap<>();
    // @urlProperty -> @folder
    private Map<String, String> urlFolderMap = new HashMap<>();

    // url -> FILEID
    private Map<String, String> urlIdMap = new HashMap<>();

    private String authenticationToken;

    private String downloadUrl;

    private static Pattern filenamePattern = Pattern.compile(".*filename=\\\"(.*)\\\".*");

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;

        process = step.getProzess();
        log.debug("process id = " + process.getId());

        // initialize VariableReplacer
        try {
            DigitalDocument dd = process.readMetadataFile().getDigitalDocument();
            Prefs prefs = process.getRegelsatz().getPreferences();
            replacer = new VariableReplacer(dd, prefs, process, step);
        } catch (ReadException | IOException | SwapException | PreferencesException e) {
            logError("Exception happened during initialization: " + e.getMessage());
        }

        // read parameters from correct block in configuration file
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);

        maxTryTimes = config.getInt("maxTryTimes", 1);
        // get download url from config
        downloadUrl = config.getString("downloadUrl");
        // replace variables in download url
        downloadUrl = replacer.replace(downloadUrl);
        authenticationToken = config.getString("authentication");
        // <fileNameProperty>
        List<HierarchicalConfiguration> fileNamePropertyConfigs = config.configurationsAt("fileNameProperty");
        for (HierarchicalConfiguration fileNameConfig : fileNamePropertyConfigs) {
            String name = fileNameConfig.getString("@urlProperty", "");
            String hash = fileNameConfig.getString("@hashProperty", "");
            String folder = fileNameConfig.getString("@folder", "master");

            try {
                String folderPath = process.getConfiguredImageFolder(folder);
                log.debug("folderPath with name '" + folder + "' is: " + folderPath);
                fileNameProperties.add(new FileNameProperty(name, hash, folderPath));

            } catch (IOException | SwapException | DAOException e) {
                String message = "Failed to get the configured image folder: " + folder;
                logError(message);
                e.printStackTrace();
            }
        }

        // <response>
        List<HierarchicalConfiguration> responsesConfigs = config.configurationsAt("response");
        for (HierarchicalConfiguration responseConfig : responsesConfigs) {
            String responseType = responseConfig.getString("@type");
            String responseMethod = responseConfig.getString("@method", "");
            String responseUrl = responseConfig.getString("@url", "");
            String responseJson = responseConfig.getString(".", "");
            String responseMessage = responseConfig.getString("@message", "");

            log.debug("responseType = " + responseType);
            log.debug("responseMethod = " + responseMethod);
            log.debug("responseUrl = " + responseUrl);
            log.debug("responseJson = " + responseJson);
            log.debug("responseMessage = " + responseMessage);

            String responseUrlReplaced = replacer.replace(responseUrl);
            String responseMessageReplaced = replacer.replace(responseMessage);
            log.debug("responseUrlReplaced = " + responseUrlReplaced);
            log.debug("responseMessageReplaced = " + responseMessageReplaced);

            SingleResponse response = new SingleResponse(responseType, responseMethod, responseUrlReplaced, responseJson, responseMessageReplaced);
            if ("success".equals(responseType)) {
                successResponses.add(response);
            } else if ("error".equals(responseType)) {
                errorResponses.add(response);
            }
        }

        log.info("DownloadAndVerifyAssets step plugin initialized");
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_download_and_verify_assets.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null; //NOSONAR
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        // your logic goes here
        prepareUrlHashAndFolderMaps();

        for (int i = 0; i < maxTryTimes; ++i) {
            urlHashMap = processAllFiles();
        }

        boolean successful = urlHashMap.isEmpty();

        if (!successful) {
            for (String fileUrl : urlHashMap.keySet()) {
                String message = "Failed " + maxTryTimes + " times to download and validate the file from: " + fileUrl;
                logError(message);
            }
        }

        log.info("DownloadAndVerifyAssets step plugin executed");
        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
    }

    /**
     * prepare the private fields urlHashMap & urlFolderMap
     */
    private void prepareUrlHashAndFolderMaps() {
        Map<String, List<String>> propertiesMap = preparePropertiesMap();

        for (FileNameProperty fileNameProperty : fileNameProperties) {
            String propertyName = fileNameProperty.getName();
            String hashPropertyName = fileNameProperty.getHash();
            if (propertiesMap.containsKey(propertyName) && propertiesMap.containsKey(hashPropertyName)) {
                log.debug("found property = " + propertyName);
                log.debug("the name of the according hash property is: " + hashPropertyName);
                List<String> hashValues = propertiesMap.get(hashPropertyName)
                        .stream()
                        .map(String::trim)
                        .toList();

                List<String> urls = propertiesMap.get(propertyName);
                log.debug("urls has " + urls.size() + " elements");
                if (!urls.isEmpty()) {
                    String folder = fileNameProperty.getFolder();
                    addUrlsToBothMaps(urls, hashValues, folder);
                }
            }
        }
    }

    /**
     * pair the input list of URLs with the input list of hashes as well as the input string folder, save the pairs accordingly into the private
     * fields urlHashMap & urlFolderMap
     * 
     * @param urls list of URLs that shall be paired with hashes and the input string folder
     * @param hashes list of hashes that shall be paired with the input list of URLs
     * @param folder target folder that shall be paired with every URL in the input list of URLs
     * @return true if everything is successfully paired and saved into both maps, false otherwise. RETURNED VALUE NOT IN USE YET.
     */
    private boolean addUrlsToBothMaps(List<String> urls, List<String> hashes, String folder) {
        if (urls == null || hashes == null) {
            log.debug("urls or hashes is null");
            return false;
        }

        if (urls.size() != hashes.size()) {
            log.debug("urls and hashes are of different sizes");
            return false;
        }

        for (int i = 0; i < urls.size(); ++i) {
            String fileId = urls.get(i);
            String url = downloadUrl.replace("{FILEID}", fileId);
            String hash = hashes.get(i);
            urlHashMap.put(url, hash);
            urlFolderMap.put(url, folder);
            urlIdMap.put(url, fileId);
            log.debug("URL-Hash pair added: " + url + " -> " + hash);
            log.debug("URL-folder pair added: " + url + " -> " + folder);
        }

        return true;
    }

    /**
     * prepare a list between the name of @urlProperty and the list of values of all process properties bearing that name
     * 
     * @return a map between the name of @urlProperty and the list of values of all process properties bearing that name
     */
    private Map<String, List<String>> preparePropertiesMap() {
        List<GoobiProperty> properties = process.getEigenschaftenList();
        Map<String, List<String>> propertiesMap = new HashMap<>();
        for (GoobiProperty property : properties) {
            String key = property.getTitel();
            String value = property.getWert();
            if (StringUtils.isNotBlank(value)) {
                propertiesMap.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        }

        return propertiesMap;
    }

    /**
     * download and verify all files
     * 
     * @param urlHashMap
     * @param urlFolderMap
     * @return a map containing infos of unsuccessful files
     */
    private Map<String, String> processAllFiles() {
        Map<String, String> unsuccessfulMap = new HashMap<>();
        // download and verify files
        for (Map.Entry<String, String> urlHashPair : urlHashMap.entrySet()) {
            String url = urlHashPair.getKey();
            String hash = urlHashPair.getValue();
            String targetFolder = urlFolderMap.get(url);
            String fileId = urlIdMap.get(url);
            try {
                processFile(url, hash, targetFolder, fileId);

            } catch (Exception e) {
                unsuccessfulMap.put(url, hash);
            }
        }

        return unsuccessfulMap;
    }

    /**
     * download and verify the file
     * 
     * @param fileUrl url of the file from where it shall be downloaded
     * @param hash expected checksum of the file
     * @param targetFolder folder to save the downloaded file
     * @throws IOException
     */
    private void processFile(String fileUrl, String hash, String targetFolder, String fileId) throws IOException {
        // prepare URL
        log.debug("downloading file from url: " + fileUrl);
        boolean successful = false;
        String fileName = Paths.get(fileUrl).getFileName().toString();

        CloseableHttpClient httpclient = null;
        HttpPost method = null;
        String actualHash = "";
        Path destination = null;
        try {

            method = new HttpPost(fileUrl);
            httpclient = HttpClientBuilder.create().build();
            if (StringUtils.isNotBlank(authenticationToken)) {
                method.setHeader("Authorization", authenticationToken);
            }

            String extension = "";
            HttpResponse response = httpclient.execute(method);
            HttpEntity entity = response.getEntity();
            for (Header h : response.getHeaders("content-disposition")) {
                String val = h.getValue();
                Matcher m = filenamePattern.matcher(val);
                if (m.find()) {
                    extension = m.group(1);
                    if (extension.contains(".")) {
                        extension = extension.substring(extension.indexOf("."));
                    }
                }
            }
            String contentType = entity.getContentType().getValue();
            if (StringUtils.isBlank(extension) && StringUtils.isNotBlank(contentType)) {
                extension = "." + contentType.substring(contentType.indexOf("/") + 1);
                if (extension.contains(";")) {
                    extension = extension.substring(0, extension.indexOf(";"));
                }
            }

            destination = Paths.get(targetFolder, fileName + extension);
            StorageProvider.getInstance().createDirectories(destination.getParent());

            try (OutputStream out = StorageProvider.getInstance().newOutputStream(destination)) {
                entity.writeTo(out);
            }

            // url is correctly formed, download the file

            try (InputStream inputStream = StorageProvider.getInstance().newInputStream(destination)) {
                actualHash = calculateHash(inputStream);
            }
            successful = true;
        } catch (Exception e) {
            log.error("Unable to connect to url " + fileUrl, e);
        }
        // check checksum
        if (!hash.equals(actualHash)) {
            String message = "checksums do not match, the file might be corrupted: " + destination;
            // delete the downloaded file
            Files.delete(destination);
            successful = false;
            throw new IOException(message);
        }

        //if file exist and is valid: send success message
        if (StorageProvider.getInstance().isFileExists(destination)) {
            reportResults(successful, fileId);

        }

    }

    /**
     * log message into both log file and journal
     * 
     * @param logType
     * @param message
     */
    private void logMessage(LogType logType, String message) {
        switch (logType) {
            case ERROR:
                log.error(message);
                return;
            case DEBUG:
                log.debug(message);
                return;
            case WARN:
                log.warn(message);
                return;
            default: // INFO
                log.info(message);
        }

        Helper.addMessageToProcessJournal(process.getId(), logType, message);
    }

    /**
     * log error
     * 
     * @param message
     */
    private void logError(String message) {
        log.error(message);
        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);
        errorsList.add(message);
    }

    /**
     * report results according to the final status
     * 
     * @param success final status
     * @return true if results are successfully reported, false otherwise
     */
    private boolean reportResults(boolean success, String fileId) {
        boolean reportSuccess = true;

        List<SingleResponse> responses = success ? successResponses : errorResponses;
        for (SingleResponse response : responses) {
            String method = response.getMethod();
            if (StringUtils.isBlank(method)) {
                // use journal
                String message = response.getMessage();
                LogType logType = success ? LogType.INFO : LogType.ERROR;
                logMessage(logType, message);

            } else {
                String url = response.getUrl();
                String jsonBase = response.getJson();
                String json = generateJsonMessage(jsonBase);
                log.debug("json = " + json);
                reportSuccess = sendResponseViaRest(method, url.replace("{FILEID}", fileId), json) && reportSuccess;
            }
        }

        String logMessage = success && reportSuccess ? "success" : "errors";
        log.debug(logMessage);

        return reportSuccess;
    }

    /**
     * generate the JSON message based on the input string
     * 
     * @param jsonBase
     * @return JSON string
     */
    private String generateJsonMessage(String jsonBase) {
        JSONObject jsonObject = StringUtils.isBlank(jsonBase) ? new JSONObject() : new JSONObject(jsonBase);
        log.debug("jsonObject = " + jsonObject.toString());

        jsonObject.put("errors", errorsList);

        return jsonObject.toString();
    }

    /**
     * send response via REST API
     * 
     * @param method REST method, options are put | post | patch
     * @param url URL of the remote system that expects this response
     * @param json JSON string as request body
     * @return true if the response is successfully sent, false otherwise
     */
    private boolean sendResponseViaRest(String method, String url, String json) {
        try {
            HttpEntityEnclosingRequestBase httpBase;
            switch (method.toLowerCase()) {
                case "put":
                    httpBase = new HttpPut(url);
                    break;
                case "post":
                    httpBase = new HttpPost(url);
                    break;
                case "patch": // not tested
                    httpBase = new HttpPatch(url);
                    break;
                default: // unknown
                    String message = "Unknown method: " + method;
                    logError(message);
                    return false;
            }
            // authentication
            if (StringUtils.isNotBlank(authenticationToken)) {
                httpBase.setHeader("Authorization", authenticationToken);
            }

            try (CloseableHttpClient client = HttpClients.createDefault()) {
                httpBase.setHeader("Accept", "application/json");
                if (StringUtils.isNotBlank(json)) {
                    httpBase.setHeader("Content-type", "application/json");
                    httpBase.setEntity(new StringEntity(json));
                }
                log.info("Executing request " + httpBase.getRequestLine());

                String responseBody = client.execute(httpBase, HttpUtils.stringResponseHandler);
                log.debug(responseBody);
                return true;
            }

        } catch (Exception e) {
            String message = "Failed to send response via REST: " + e;
            logError(message);
            e.printStackTrace();
            return false;
        }
    }

    @Data
    @AllArgsConstructor
    private class SingleResponse {
        private String type;
        private String method;
        private String url;
        private String json;
        private String message;
    }

    @Data
    @AllArgsConstructor
    private class FileNameProperty {
        private String name;
        private String hash;
        private String folder;
    }

    private static String calculateHash(InputStream is) throws IOException {
        MessageDigest shamd = null;
        try {
            shamd = MessageDigest.getInstance("SHA-256");

            try (DigestInputStream dis = new DigestInputStream(is, shamd)) {
                int n = 0;
                byte[] buffer = new byte[8092];
                while (n != -1) {
                    n = dis.read(buffer);
                }
                is.close();
            }
            String shaString;
            shaString = getShaString(shamd);

            return shaString;
        } catch (NoSuchAlgorithmException e1) {
            return null;
        }
    }

    private static String getShaString(MessageDigest messageDigest) {
        BigInteger bigInt = new BigInteger(1, messageDigest.digest());
        StringBuilder sha256 = new StringBuilder(bigInt.toString(16).toLowerCase());
        while (sha256.length() < 64) {
            sha256.insert(0, "0");
        }

        return sha256.toString();
    }

}
