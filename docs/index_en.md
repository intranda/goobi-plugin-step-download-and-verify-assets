---
title: Downloading and verifying files
identifier: intranda_step_download_and_verify_assets
published: true
description: This step plugin makes it possible to download files and verify them with checksums that exist as process properties. The validation result is saved within the journal.
keywords:
    - Goobi workflow
    - Plugin
    - Step Plugin
---
## Introduction
This plugin reads URLs or hash values from several configured process properties, downloads the files from the defined URL and then compares them with the corresponding hash value. Finally, several responses can be given, depending on whether the status is `success` or `error`. These responses can be sent to another system via REST or simply logged within the journal.


## Installation
To install the plugin, the following file must be installed:

```bash
/opt/digiverso/goobi/plugins/step/plugin_intranda_step_download_and_verify_assets-base.jar
```

The configuration file is usually located here:

```bash
/opt/digiverso/goobi/config/plugin_intranda_step_download_and_verify_assets.xml
```

![Selection of the plugin for performing the step](screen1_en.png)

## Configuration
The content of this configuration file looks like the following example:

```xml
<config_plugin>
    <!--
        order of configuration is:
          1.) project name and step name matches
          2.) step name matches and project is *
          3.) project name matches and step name is *
          4.) project name and step name are *
    -->

    <config>
        <!-- which projects to use for (can be more then one, otherwise use *) -->
        <project>*</project>
        <step>*</step>

        <!-- Configure here how many times shall be maximally tried before reporting final results. OPTIONAL. DEFAULT 1. -->
        <maxTryTimes>3</maxTryTimes>

        <!-- This tag accepts the following three attributes:
            - @urlProperty: name of the property that holds the URL of the file
            - @hashProperty: name of the property that holds the checksum of the file
            - @folder: configured name of the target folder that shall be used to download the file. OPTIONAL. DEFAULT master.
        -->
        <fileNameProperty urlProperty="DraftUri" hashProperty="DraftHash" folder="master" />
        <fileNameProperty urlProperty="AssetUriSplitted" hashProperty="AssetHashSplitted" folder="master" />

        <!-- A response tag accepts four attributes:
            - @type: success | error. Determines by which cases this configured response shall be activated.
            - @method: OPTIONAL. If not configured or configured blankly, then the response will be performed via journal logs. Non-blank configuration options are: put | post | patch.
            - @url: URL to the target system expecting this response. MANDATORY if @method is not blank..
            - @message: Message that shall be logged into journal. ONLY needed when @method is blank.
            - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
            One can also define a JSON string inside a pair of these tags, which will be used as JSON body to shoot a REST request.
        -->
        <!-- Usage of Goobi variables in @url as well as @message is allowed. -->
        <response type="success" method="put" url="URL_ZU_BACH/upload_successful/{meta.ThesisId}" />

        <!-- For error cases there is no need for a response back to BACH, but an error message should be logged into journal. -->
        <!-- Log ERROR_MESSAGE into journal as a signal of errors -->
        <response type="error" message="ERROR_MESSAGE" />

        <!-- Example for REST calls with json body -->
        <!--
        <response type="success" method="put" url="CHANGE_ME">
        {
           "id": 0,
           "name": "string",
           "value": "string"
        }
        </response>
        -->

    </config>

</config_plugin>
```

The `<config>` block can occur repeatedly for different projects or work steps in order to be able to perform different actions within different workflows.

| Value | Description |
| :--- | :--- |
| `project` | This parameter defines which project the current block `<config>` should apply to. The name of the project is used here. This parameter can occur several times per `<config>` block. |
| `step` | This parameter controls which work steps the `<config>` block should apply to. The name of the work step is used here. This parameter can occur several times per `<config>` block. |
| `maxTryTimes` | This value defines the maximum number of attempts to be made before feedback must be given. This parameter is optional and has the default value `1`. |
| `fileNameProperty` | This parameter controls the part for downloading and verifying the files. It accepts three attributes. `@urlProperty` defines the name of the process property that contains the URL of the file. `@hashProperty` defines the name of the process property that contains the checksum of the file. The attribute `@folder` is optional and has the default value `master`. It controls where the downloaded files are to be saved.  |
| `response` | This optional parameter can be used to provide multiple responses after downloading and verifying the files. It accepts four attributes and a JSON text for REST requests with a JSON body. More details and examples can be found in the comments of the sample configuration file. |
