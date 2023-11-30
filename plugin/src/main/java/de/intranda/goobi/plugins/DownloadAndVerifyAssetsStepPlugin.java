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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
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
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.io.FilenameUtils;
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
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class DownloadAndVerifyAssetsStepPlugin implements IStepPluginVersion2 {
    
    @Getter
    private String title = "intranda_step_download_and_verify_assets";
    @Getter
    private Step step;

    private String returnPath;

    //    private List<String> fileNameProperties = new ArrayList<>();

    private List<FileNameProperty> fileNameProperties = new ArrayList<>();

    private List<SingleResponse> successResponses = new ArrayList<>();
    private List<SingleResponse> errorResponses = new ArrayList<>();

    private List<String> errorsList = new ArrayList<>();

    private int maxTryTimes;

    private Process process;
    private String masterFolder;

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
                
        // read parameters from correct block in configuration file
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);
        log.info("DownloadAndVerifyAssets step plugin initialized");

        List<HierarchicalConfiguration> fileNamePropertyConfigs = config.configurationsAt("fileNameProperty");
        for (HierarchicalConfiguration fileNameConfig : fileNamePropertyConfigs) {
            String name = fileNameConfig.getString(".");
            String hash = fileNameConfig.getString("@hashProperty", "");
            String folder = fileNameConfig.getString("@folder", "master");
            fileNameProperties.add(new FileNameProperty(name, hash, folder));
        }

        //        List<Object> properties = config.getList("fileNameProperty");
        //        for (Object property : properties) {
        //            fileNameProperties.add(String.valueOf(property));
        //        }

        maxTryTimes = config.getInt("maxTryTimes", 3);

        process = step.getProzess();
        log.debug("process id = " + process.getId());

        try {
            masterFolder = process.getImagesOrigDirectory(false);
            log.debug("masterFolder = " + masterFolder);

        } catch (IOException | SwapException | DAOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        List<HierarchicalConfiguration> responsesConfigs = config.configurationsAt("response");
        for (HierarchicalConfiguration responseConfig : responsesConfigs) {
            String responseType = responseConfig.getString("@type");
            String responseMethod = responseConfig.getString("@method");
            String responseUrl = responseConfig.getString("@url");
            String responseJson = responseConfig.getString(".");

            log.debug("responseType = " + responseType);
            log.debug("responseMethod = " + responseMethod);
            log.debug("responseUrl = " + responseUrl);
            log.debug("responseJson = " + responseJson);

            SingleResponse response = new SingleResponse(responseType, responseMethod, responseUrl, responseJson);
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

        // retrieve urls
        List<String> fileUrlsList = prepareFileUrlsList();

        for (int i = 0; i < maxTryTimes; ++i) {
            // get the list of files that are not successfully processed yet
            fileUrlsList = processAllFiles(fileUrlsList);
        }

        boolean successful = fileUrlsList.isEmpty();

        if (!successful) {
            for (String fileUrl : fileUrlsList) {
                String message = "Failed " + maxTryTimes + " times to download and validate the file from: " + fileUrl;
                logError(message);
            }
        }

        // report success / errors via REST to the BACH system
        reportResults(successful);

        log.info("DownloadAndVerifyAssets step plugin executed");
        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
    }

    private List<String> prepareFileUrlsList() {
        List<String> urlsList = new ArrayList<>();
        //        Map<String, String> propertiesMap = preparePropertiesMap();
        //        for (String fileNameProperty : fileNameProperties) {
        //            if (propertiesMap.containsKey(fileNameProperty)) {
        //                log.debug("found property = " + fileNameProperty);
        //                String propertyValue = propertiesMap.get(fileNameProperty);
        //                log.debug("propertyValue = " + propertyValue);
        //                addUrlsToList(propertyValue, urlsList);
        //            }
        //        }

        Map<String, List<String>> propertiesMap = preparePropertiesMap();

        for (FileNameProperty fileNameProperty : fileNameProperties) {
            String propertyName = fileNameProperty.getName();
            if (propertiesMap.containsKey(propertyName)) {
                log.debug("found property = " + propertyName);
                String hashProperty = fileNameProperty.getHash();
                log.debug("the according hash property is: " + hashProperty);
                List<String> propertyValues = propertiesMap.get(propertyName);
                log.debug("propertyValue has " + propertyValues.size() + " elements");
                addUrlsToList(propertyValues, urlsList);
            }
        }

        return urlsList;
    }

    //    private Map<String, String> preparePropertiesMap() {
    //        List<Processproperty> properties = process.getEigenschaftenList();
    //        Map<String, String> propertiesMap = new HashMap<>();
    //        for (Processproperty property : properties) {
    //            String key = property.getTitel();
    //            String value = property.getWert();
    //            propertiesMap.put(key, value);
    //        }
    //
    //        return propertiesMap;
    //    }

    private Map<String, List<String>> preparePropertiesMap() {
        List<Processproperty> properties = process.getEigenschaftenList();
        Map<String, List<String>> propertiesMap = new HashMap<>();
        for (Processproperty property : properties) {
            String key = property.getTitel();
            String value = property.getWert();

            propertiesMap.computeIfAbsent(key, k -> new ArrayList<>()).add(value);

            //            if (propertiesMap.containsKey(key)) {
            //                propertiesMap.get(key).add(value);
            //            } else {
            //                propertiesMap.put(key, Arrays.asList(value));
            //            }
        }

        return propertiesMap;
    }

    private void addUrlsToList(String propertyValue, List<String> urlsList) {
        if (!propertyValue.contains(",")) {
            urlsList.add(propertyValue);
            return;
        }

        // multiple urls separated by comma
        String[] urls = propertyValue.split(",");
        for (String url : urls) {
            urlsList.add(url);
        }
    }

    private void addUrlsToList(List<String> propertyValues, List<String> urlsList) {
        for (String propertyValue : propertyValues) {
            addUrlsToList(propertyValue, urlsList);
        }
    }

    private List<String> processAllFiles(List<String> fileUrlsList) {
        List<String> unsuccessfulList = new ArrayList<>();
        // download and verify files
        for (String fileUrl : fileUrlsList) {
            log.debug("fileUrl = " + fileUrl);
            try {
                processFile(fileUrl, masterFolder);
            } catch (Exception e) {
                unsuccessfulList.add(fileUrl);
            }
        }

        return unsuccessfulList;
    }

    private void processFile(String fileUrl, String targetFolder) throws IOException {
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
        Path targetPath = Path.of(targetFolder, fileName);

        // url is correctly formed, download the file
        long checksumOrigin = downloadFile(url, targetPath);

        // check checksum
        checkFileChecksum(checksumOrigin, targetPath);
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

    private void checkFileChecksum(long checksumOrigin, Path filePath) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream in = new BufferedInputStream(new FileInputStream(filePath.toFile()))) {
            int c;
            while ((c = in.read()) != -1) {
                crc.update(c);
            }
        }

        // the following two lines are used to test the error reporting logic
        //        boolean useChecksum = Math.random() > 0.5; // NOSONAR
        //        long checksum = useChecksum ? crc.getValue() : -1; // NOSONAR
        long checksum = crc.getValue();
        log.debug("crc value of downloaded file = " + checksum);

        if (checksum != checksumOrigin) {
            String message = "checksums do not match, the file might be corrupted: " + filePath;
            // delete the downloaded file
            filePath.toFile().delete();
            throw new IOException(message);
        }
    }

    private void logError(String message) {
        log.error(message);
        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);
        errorsList.add(message);
    }

    private void reportResults(boolean success) {
        String logMessage = success ? "success" : "errors";
        log.debug(logMessage);

        List<SingleResponse> responses = success ? successResponses : errorResponses;
        for (SingleResponse response : responses) {
            String method = response.getMethod();
            String url = response.getUrl();
            String jsonBase = response.getJson();
            String json = generateJsonMessage(jsonBase);
            log.debug("json = " + json);
            responseViaRest(method, url, json);
        }
    }

    private String generateJsonMessage(String jsonBase) {
        JSONObject jsonObject = jsonBase == null ? new JSONObject() : new JSONObject(jsonBase);
        log.debug("jsonObject = " + jsonObject.toString());

        jsonObject.put("errors", errorsList);

        return jsonObject.toString();
    }

    private void responseViaRest(String method, String url, String json) {
        HttpEntityEnclosingRequestBase httpBase;
        switch (method.toLowerCase()) {
            case "put":
                httpBase = new HttpPut(url);
                break;
            case "post":
                httpBase = new HttpPost(url);
                break;
            case "patch":
                httpBase = new HttpPatch(url);
            default: // unknown
                return;
        }

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            httpBase.setHeader("Accept", "application/json");
            httpBase.setHeader("Content-type", "application/json");
            httpBase.setEntity(new StringEntity(json));

            log.info("Executing request " + httpBase.getRequestLine());

            String responseBody = client.execute(httpBase, RESPONSE_HANDLER);
            log.debug(responseBody);

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Data
    @AllArgsConstructor
    private class SingleResponse {
        private String type;
        private String method;
        private String url;
        private String json;
    }

    @Data
    private class FileNameProperty {
        private String name;
        private String hash;
        private String folder;

        public FileNameProperty(String name, String hash, String folder) {
            this.name = name;
            this.hash = hash;
            this.folder = folder;
        }

        public FileNameProperty(String name, String hash) {
            this.name = name;
            this.hash = hash;
            this.folder = "master";
        }
    }


}
