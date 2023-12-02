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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
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
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
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
    private static final StorageProviderInterface storageProvider = StorageProvider.getInstance();
    
    @Getter
    private String title = "intranda_step_download_and_verify_assets";
    @Getter
    private Step step;

    private String returnPath;

    private List<FileNameProperty> fileNameProperties = new ArrayList<>();

    private List<SingleResponse> successResponses = new ArrayList<>();
    private List<SingleResponse> errorResponses = new ArrayList<>();

    private List<String> errorsList = new ArrayList<>();

    private int maxTryTimes;

    private Process process;
    private VariableReplacer replacer;

    private Map<String, Long> urlHashMap = new HashMap<>();
    private Map<String, String> urlFolderMap = new HashMap<>();

    // create a custom response handler
    private static final ResponseHandler<String> RESPONSE_HANDLER = response -> {
        int status = response.getStatusLine().getStatusCode();
        if (status >= 200 && status < 300) {
            HttpEntity entity = response.getEntity();
            return entity != null ? EntityUtils.toString(entity) : null;
        } else {
            throw new ClientProtocolException("Unexpected response status: " + status);
        }
    };

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
        log.info("DownloadAndVerifyAssets step plugin initialized");

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

        maxTryTimes = config.getInt("maxTryTimes", 1);

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
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.FULL;
        // return PluginGuiType.PART;
        // return PluginGuiType.PART_AND_FULL;
        // return PluginGuiType.NONE;
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
        return null;
    }
    
    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        // your logic goes here
        prepareUrlHashMap();

        for (int i = 0; i < maxTryTimes; ++i) {
            urlHashMap = processAllFiles(urlHashMap, urlFolderMap);
        }

        boolean successful = urlHashMap.isEmpty();

        if (!successful) {
            for (String fileUrl : urlHashMap.keySet()) {
                String message = "Failed " + maxTryTimes + " times to download and validate the file from: " + fileUrl;
                logError(message);
            }
        }

        // report success / errors via REST to the BACH system
        successful = reportResults(successful) && successful;

        log.info("DownloadAndVerifyAssets step plugin executed");
        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
    }

    private void prepareUrlHashMap() {
        Map<String, List<String>> propertiesMap = preparePropertiesMap();

        for (FileNameProperty fileNameProperty : fileNameProperties) {
            String propertyName = fileNameProperty.getName();
            String hashPropertyName = fileNameProperty.getHash();
            if (propertiesMap.containsKey(propertyName) && propertiesMap.containsKey(hashPropertyName)) {
                log.debug("found property = " + propertyName);
                log.debug("the name of the according hash property is: " + hashPropertyName);
                List<Long> hashValues = propertiesMap.get(hashPropertyName)
                        .stream()
                        .map(String::trim)
                        .map(Long::valueOf)
                        .collect(Collectors.toList());

                List<String> urls = propertiesMap.get(propertyName);
                log.debug("urls has " + urls.size() + " elements");

                String folder = fileNameProperty.getFolder();
                addUrlHashPairs(urls, hashValues, folder);
            }
        }
    }

    private boolean addUrlHashPairs(List<String> urls, List<Long> hashes, String folder) {
        if (urls == null || hashes == null) {
            log.debug("urls or hashes is null");
            return false;
        }

        if (urls.size() != hashes.size()) {
            log.debug("urls and hashes are of different sizes");
            return false;
        }

        for (int i = 0; i < urls.size(); ++i) {
            String url = urls.get(i);
            long hash = hashes.get(i);
            urlHashMap.put(url, hash);
            urlFolderMap.put(url, folder);
            log.debug("URL-Hash pair added: " + url + " -> " + hash);
            log.debug("URL-folder pair added: " + url + " -> " + folder);
        }

        return true;
    }

    private Map<String, List<String>> preparePropertiesMap() {
        List<Processproperty> properties = process.getEigenschaftenList();
        Map<String, List<String>> propertiesMap = new HashMap<>();
        for (Processproperty property : properties) {
            String key = property.getTitel();
            String value = property.getWert();

            propertiesMap.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

        return propertiesMap;
    }

    private Map<String, Long> processAllFiles(Map<String, Long> urlHashMap, Map<String, String> urlFolderMap) {
        Map<String, Long> unsuccessfulMap = new HashMap<>();
        // download and verify files
        for (Map.Entry<String, Long> urlHashPair : urlHashMap.entrySet()) {
            String url = urlHashPair.getKey();
            long hash = urlHashPair.getValue();
            String targetFolder = urlFolderMap.get(url);

            try {
                processFile(url, hash, targetFolder);

            } catch (Exception e) {
                unsuccessfulMap.put(url, hash);
            }
        }

        return unsuccessfulMap;
    }

    private void processFile(String fileUrl, long hash, String targetFolder) throws IOException {
        // prepare URL
        log.debug("downloading file from url: " + fileUrl);
        URL url = null;
        try {
            url = new URL(fileUrl);

        } catch (MalformedURLException e) {
            String message = "the input URL is malformed: " + fileUrl;
            logError(message);
            return;
        }

        String fileName = getFileNameFromUrl(url);
        Path targetFolderPath = Path.of(targetFolder);
        storageProvider.createDirectories(targetFolderPath);
        Path targetPath = targetFolderPath.resolve(fileName);

        // url is correctly formed, download the file
        long checksumDownloaded = downloadFile(url, targetPath);

        // check checksum
        if (hash != checksumDownloaded) {
            String message = "checksums do not match, the file might be corrupted: " + targetPath;
            // delete the downloaded file
            targetPath.toFile().delete();
            throw new IOException(message);
        }
    }

    /**
     * get the file name from a URL object
     * 
     * @param url the URL object
     * @return the full file name including the file extension
     */
    private String getFileNameFromUrl(URL url) {
        String urlFileName = url.getFile();
        log.debug("urlFileName = " + urlFileName);

        String fileName = FilenameUtils.getName(urlFileName);
        log.debug("fileName = " + fileName);

        return fileName;
    }

    /**
     * download the file from the given url
     * 
     * @param url URL of the file
     * @param targetPath targeted full path of the file
     * @throws IOException
     */
    private long downloadFile(URL url, Path targetPath) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream in = new CheckedInputStream(url.openStream(), crc);
                ReadableByteChannel readableByteChannel = Channels.newChannel(in);
                FileOutputStream outputStream = new FileOutputStream(targetPath.toString())) {

            FileChannel fileChannel = outputStream.getChannel();
            fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
        }

        long crc32 = crc.getValue();
        log.debug("crc32 = " + crc32);
        return crc32;
    }

    private void logMessage(LogType logType, String message) {
        switch (logType) {
            case ERROR:
                log.error(message);
            case DEBUG:
                log.debug(message);
            case WARN:
                log.warn(message);
            default: // INFO
                log.info(message);
        }

        Helper.addMessageToProcessJournal(process.getId(), logType, message);
    }

    private void logError(String message) {
        log.error(message);
        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);
        errorsList.add(message);
    }

    private boolean reportResults(boolean success) {
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
                reportSuccess = responseViaRest(method, url, json) && reportSuccess;
            }
        }

        String logMessage = success && reportSuccess ? "success" : "errors";
        log.debug(logMessage);

        return reportSuccess;
    }

    private String generateJsonMessage(String jsonBase) {
        JSONObject jsonObject = StringUtils.isBlank(jsonBase) ? new JSONObject() : new JSONObject(jsonBase);
        log.debug("jsonObject = " + jsonObject.toString());

        jsonObject.put("errors", errorsList);

        return jsonObject.toString();
    }

    private boolean responseViaRest(String method, String url, String json) {
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
                default: // unknown
                    String message = "Unknown method: " + method;
                    logError(message);
                    return false;
            }

            try (CloseableHttpClient client = HttpClients.createDefault()) {
                httpBase.setHeader("Accept", "application/json");
                httpBase.setHeader("Content-type", "application/json");
                httpBase.setEntity(new StringEntity(json));

                log.info("Executing request " + httpBase.getRequestLine());

                String responseBody = client.execute(httpBase, RESPONSE_HANDLER);
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

}
