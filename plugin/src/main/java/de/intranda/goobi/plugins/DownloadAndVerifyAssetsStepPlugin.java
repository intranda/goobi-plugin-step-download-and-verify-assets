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

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.io.FilenameUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
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
    @Getter
    private String value;
    @Getter 
    private boolean allowTaskFinishButtons;
    private String returnPath;

    private List<String> fileNameProperties = new ArrayList<>();

    private Process process;
    private String masterFolder;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
                
        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        value = myconfig.getString("value", "default value"); 
        allowTaskFinishButtons = myconfig.getBoolean("allowTaskFinishButtons", false);
        log.info("DownloadAndVerifyAssets step plugin initialized");
        log.debug("value = " + value);

        List<Object> properties = myconfig.getList("fileNameProperty");
        for (Object property : properties) {
            fileNameProperties.add(String.valueOf(property));
        }

        process = step.getProzess();
        log.debug("process id = " + process.getId());

        try {
            masterFolder = process.getImagesOrigDirectory(false);
            log.debug("masterFolder = " + masterFolder);

        } catch (IOException | SwapException | DAOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
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
        boolean successful = true;
        // your logic goes here

        // retrieve urls
        List<String> fileUrlsList = prepareFileUrlsList();
        // download and verify files
        for (String fileUrl : fileUrlsList) {
            log.debug("fileUrl = " + fileUrl);
            processFile(fileUrl, masterFolder);
        }

        log.info("DownloadAndVerifyAssets step plugin executed");
        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
    }

    private void processFile(String fileUrl, String targetFolder) {
        // prepare URL
        log.debug("downloading file from url: " + fileUrl);
        // check url
        URL url = null;
        try {
            url = new URL(fileUrl);
        } catch (MalformedURLException e) {
            String message = "the input URL is malformed: " + fileUrl;
            //            reportError(message);
            log.error(message);
            return;
        }

        String fileName = getFileNameFromUrl(url);
        Path targetPath = Path.of(targetFolder, fileName);

        // url is correctly formed, download the file
        try {
            long checksumOrigin = downloadFile(url, targetPath);

            // check checksum
            boolean passed = checkFileChecksum(checksumOrigin, targetPath);
            log.debug("passed = " + passed);

        } catch (IOException e) {
            String message = "failed to download the file from " + fileUrl;
            //            reportError(message);
            log.error(message);
        }


    }

    private void processFileNameProperty(String fileNameProperty) {
        log.debug("processing fileNameProperty: " + fileNameProperty);
        // get the value of the process property

        // check if the value contains multiple urls separated by comma, if so split them

    }

    private List<String> prepareFileUrlsList() {
        List<String> urlsList = new ArrayList<>();
        Map<String, String> propertiesMap = preparePropertiesMap();
        for (String fileNameProperty : fileNameProperties) {
            if (propertiesMap.containsKey(fileNameProperty)) {
                log.debug("found property = " + fileNameProperty);
                String propertyValue = propertiesMap.get(fileNameProperty);
                log.debug("propertyValue = " + propertyValue);
                addUrlsToList(propertyValue, urlsList);
            }
        }

        return urlsList;
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

    private Map<String, String> preparePropertiesMap() {
        List<Processproperty> properties = process.getEigenschaftenList();
        Map<String, String> propertiesMap = new HashMap<>();
        for (Processproperty property : properties) {
            String key = property.getTitel();
            String value = property.getWert();
            propertiesMap.put(key, value);
        }

        return propertiesMap;
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

    private boolean checkFileChecksum(long checksumOrigin, Path filePath) {
        CRC32 crc = new CRC32();
        try (InputStream in = new BufferedInputStream(new FileInputStream(filePath.toFile()))) {
            int c;
            while ((c = in.read()) != -1) {
                crc.update(c);
            }

        } catch (IOException e) {
            String message = "checksums do not match";
            log.error(message);
            return false;
        }

        long checksum = crc.getValue();
        log.debug("crc value of downloaded file = " + checksum);

        return checksum == checksumOrigin;
    }
}
