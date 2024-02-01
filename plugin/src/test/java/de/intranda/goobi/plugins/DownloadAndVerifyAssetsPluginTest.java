package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.MatchResult;

import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Project;
import org.goobi.beans.Ruleset;
import org.goobi.beans.Step;
import org.goobi.beans.User;
import org.goobi.production.enums.PluginReturnValue;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.metadaten.MetadatenHelper;
import de.sub.goobi.persistence.managers.MetadataManager;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.PropertyManager;
import io.goobi.workflow.api.connection.HttpUtils;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ MetadatenHelper.class, VariableReplacer.class, ConfigurationHelper.class, ProcessManager.class, MetadataManager.class, Helper.class,
        HttpUtils.class, PropertyManager.class })
@PowerMockIgnore({ "javax.management.*", "javax.xml.*", "org.xml.*", "org.w3c.*", "javax.net.ssl.*", "jdk.internal.reflect.*" })
public class DownloadAndVerifyAssetsPluginTest {

    private static String resourcesFolder;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File processDirectory;
    private File metadataDirectory;
    private Process process;
    private Step step;
    private Prefs prefs;

    @BeforeClass
    public static void setUpClass() throws Exception {
        resourcesFolder = "src/test/resources/"; // for junit tests in eclipse

        if (!Files.exists(Paths.get(resourcesFolder))) {
            resourcesFolder = "target/test-classes/"; // to run mvn test from cli or in jenkins
        }

        String log4jFile = resourcesFolder + "log4j2.xml"; // for junit tests in eclipse

        System.setProperty("log4j.configurationFile", log4jFile);
    }

    @Test
    public void testConstructor() throws Exception {
        DownloadAndVerifyAssetsStepPlugin plugin = new DownloadAndVerifyAssetsStepPlugin();
        assertNotNull(plugin);
    }

    @Test
    public void testInit() {
        DownloadAndVerifyAssetsStepPlugin plugin = new DownloadAndVerifyAssetsStepPlugin();
        plugin.initialize(step, "something");
        assertEquals(step.getTitel(), plugin.getStep().getTitel());
    }

    // does not work without access to BACH server, enable it locally
    //    @Ignore
    @Test
    public void testDownload() throws IOException {
        DownloadAndVerifyAssetsStepPlugin plugin = new DownloadAndVerifyAssetsStepPlugin();
        plugin.initialize(step, "something");
        assertEquals(PluginReturnValue.FINISH, plugin.run());
    }

    @Before
    public void setUp() throws Exception {
        metadataDirectory = folder.newFolder("metadata");
        processDirectory = new File(metadataDirectory + File.separator + "1");
        processDirectory.mkdirs();
        String metadataDirectoryName = metadataDirectory.getAbsolutePath() + File.separator;
        Path metaSource = Paths.get(resourcesFolder, "meta.xml");
        Path metaTarget = Paths.get(processDirectory.getAbsolutePath(), "meta.xml");
        Files.copy(metaSource, metaTarget);

        PowerMock.mockStatic(ConfigurationHelper.class);
        ConfigurationHelper configurationHelper = EasyMock.createMock(ConfigurationHelper.class);
        EasyMock.expect(ConfigurationHelper.getInstance()).andReturn(configurationHelper).anyTimes();
        EasyMock.expect(configurationHelper.getMetsEditorLockingTime()).andReturn(1800000l).anyTimes();
        EasyMock.expect(configurationHelper.isAllowWhitespacesInFolder()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.useS3()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.isUseProxy()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.isUseMasterDirectory()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.isCreateMasterDirectory()).andReturn(false).anyTimes();

        EasyMock.expect(configurationHelper.getGoobiContentServerTimeOut()).andReturn(60000).anyTimes();
        EasyMock.expect(configurationHelper.getMetadataFolder()).andReturn(metadataDirectoryName).anyTimes();
        EasyMock.expect(configurationHelper.getRulesetFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getProcessImagesMainDirectoryName()).andReturn("00469418X_media").anyTimes();

        EasyMock.expect(configurationHelper.getConfigurationFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getGoobiFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getScriptsFolder()).andReturn(resourcesFolder).anyTimes();

        EasyMock.expect(configurationHelper.getNumberOfMetaBackups()).andReturn(0).anyTimes();
        EasyMock.replay(configurationHelper);

        PowerMock.mockStatic(Helper.class);
        Helper.addMessageToProcessJournal(EasyMock.anyInt(), EasyMock.anyObject(), EasyMock.anyString());
        Helper.addMessageToProcessJournal(EasyMock.anyInt(), EasyMock.anyObject(), EasyMock.anyString());
        Helper.addMessageToProcessJournal(EasyMock.anyInt(), EasyMock.anyObject(), EasyMock.anyString());

        PowerMock.mockStatic(VariableReplacer.class);
        EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject()))
                .andAnswer(
                        new IAnswer<String>() {
                            @Override
                            public String answer() throws Throwable {
                                String val = (String) EasyMock.getCurrentArguments()[0];
                                return val.replace("{meta.ThesisId}", "106");
                            }
                        })
                .anyTimes();

        Iterable<MatchResult> results = EasyMock.createMock(Iterable.class);
        Iterator<MatchResult> iter = EasyMock.createMock(Iterator.class);
        EasyMock.expect(results.iterator()).andReturn(iter).anyTimes();
        EasyMock.expect(iter.hasNext()).andReturn(false).anyTimes();

        EasyMock.expect(VariableReplacer.findRegexMatches(EasyMock.anyString(), EasyMock.anyString())).andReturn(results).anyTimes();
        EasyMock.replay(results);
        EasyMock.replay(iter);

        PowerMock.replay(VariableReplacer.class);
        prefs = new Prefs();
        prefs.loadPrefs(resourcesFolder + "ruleset.xml");
        Fileformat ff = new MetsMods(prefs);
        ff.read(metaTarget.toString());

        PowerMock.mockStatic(MetadatenHelper.class);
        EasyMock.expect(MetadatenHelper.getMetaFileType(EasyMock.anyString())).andReturn("mets").anyTimes();
        EasyMock.expect(MetadatenHelper.getFileformatByName(EasyMock.anyString(), EasyMock.anyObject())).andReturn(ff).anyTimes();
        EasyMock.expect(MetadatenHelper.getMetadataOfFileformat(EasyMock.anyObject(), EasyMock.anyBoolean()))
                .andReturn(Collections.emptyMap())
                .anyTimes();
        PowerMock.replay(MetadatenHelper.class);

        PowerMock.mockStatic(MetadataManager.class);
        MetadataManager.updateMetadata(1, Collections.emptyMap());

        MetadataManager.updateMetadata(1, Collections.emptyMap());

        MetadataManager.updateJSONMetadata(1, Collections.emptyMap());

        PowerMock.mockStatic(PropertyManager.class);
        EasyMock.expect(PropertyManager.getProcessPropertiesForProcess(EasyMock.anyInt())).andReturn(Collections.emptyList()).anyTimes();
        PropertyManager.saveProcessProperty(EasyMock.anyObject());
        PropertyManager.saveProcessProperty(EasyMock.anyObject());
        PropertyManager.saveProcessProperty(EasyMock.anyObject());
        PropertyManager.saveProcessProperty(EasyMock.anyObject());
        PropertyManager.saveProcessProperty(EasyMock.anyObject());

        PowerMock.replay(PropertyManager.class);
        PowerMock.replay(MetadataManager.class);
        PowerMock.replay(ConfigurationHelper.class);
        PowerMock.replay(Helper.class);

        process = getProcess();

        Ruleset ruleset = PowerMock.createMock(Ruleset.class);
        ruleset.setTitel("ruleset");
        ruleset.setDatei("ruleset.xml");
        EasyMock.expect(ruleset.getDatei()).andReturn("ruleset.xml").anyTimes();
        process.setRegelsatz(ruleset);
        EasyMock.expect(ruleset.getPreferences()).andReturn(prefs).anyTimes();
        PowerMock.replay(ruleset);
        createProcessProperties();
    }

    private void createProcessProperties() {
        List<Processproperty> props = new ArrayList<>();
        {
            Processproperty property = new Processproperty();
            property.setId(1);
            property.setProzess(process);
            property.setTitel("AttachmentIDSplitted");
            property.setWert("107");
            props.add(property);
        }

        {
            Processproperty property = new Processproperty();
            property.setId(1);
            property.setProzess(process);
            property.setTitel("AttachmentHashSplitted");
            property.setWert("1eae07b41cb3323ab370d3ddd78de440ffe6d581d1d3736c086b8949d24b35da1");
            props.add(property);
        }

        process.setEigenschaften(props);
    }

    public Process getProcess() {
        Project project = new Project();
        project.setTitel("SampleProject");

        Process process = new Process();
        process.setTitel("00469418X");
        process.setProjekt(project);
        process.setId(1);
        List<Step> steps = new ArrayList<>();
        step = new Step();
        step.setReihenfolge(1);
        step.setProzess(process);
        step.setTitel("test step");
        step.setBearbeitungsstatusEnum(StepStatus.OPEN);
        User user = new User();
        user.setVorname("Firstname");
        user.setNachname("Lastname");
        step.setBearbeitungsbenutzer(user);
        steps.add(step);

        process.setSchritte(steps);

        try {
            createProcessDirectory(processDirectory);
        } catch (IOException e) {
        }

        return process;
    }

    private void createProcessDirectory(File processDirectory) throws IOException {

        // image folder
        File imageDirectory = new File(processDirectory.getAbsolutePath(), "images");
        imageDirectory.mkdir();
        // master folder
        File masterDirectory = new File(imageDirectory.getAbsolutePath(), "00469418X_master");
        masterDirectory.mkdir();

        // media folder
        File mediaDirectory = new File(imageDirectory.getAbsolutePath(), "00469418X_media");
        mediaDirectory.mkdir();

        // TODO add some file
    }
}
