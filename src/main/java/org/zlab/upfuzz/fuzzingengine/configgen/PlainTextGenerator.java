package org.zlab.upfuzz.fuzzingengine.configgen;

import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import org.zlab.upfuzz.docker.DockerCluster;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.fuzzingengine.Config;

public class PlainTextGenerator extends ConfigFileGenerator {
    private static final Logger logger = LogManager
            .getLogger(PlainTextGenerator.class);

    public Path defaultFilePath;
    public Path defaultNewFilePath;

    public String generationType;

    public PlainTextGenerator(Path defaultFilePath, Path defaultNewFilePath,
            String generationType, Path generateFolderPath) {
        super(generateFolderPath);
        this.defaultFilePath = defaultFilePath;
        this.defaultNewFilePath = defaultNewFilePath;
        this.generationType = generationType;
    }

    public PlainTextGenerator(Path defaultFilePath, String generationType,
            Path generateFolderPath) {
        super(generateFolderPath);
        this.defaultFilePath = defaultFilePath;
        this.generationType = generationType;
    }

    public int generate(Map<String, String> key2vals,
            Map<String, String> key2type,
            Map<String, String> newkey2vals,
            Map<String, String> newkey2type) {
        return generate(true);
    }

    @Override
    public int generate(Map<String, String> key2vals,
            Map<String, String> key2type) {
        return generate(false);
    }

    @Override
    public int generate(boolean isUpgrade) {
        Path savePath = generateFolderPath
                .resolve(String.format("test%d", fileNameIdx));
        Path oriConfig = savePath.resolve("oriconfig");
        oriConfig.toFile().mkdirs();
        Path oriSavePath = oriConfig.resolve(defaultFilePath.getFileName());
        if (generationType.equals("regionservers")) {
            try {
                FileWriter writerOri = new FileWriter(oriSavePath.toFile());
                for (String regionName : Config.getConf()
                        .getHBaseRegionServers()) {
                    writerOri.write(regionName + "\n");
                }
                writerOri.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (isUpgrade) {
            Path upConfig = savePath.resolve("upconfig");
            upConfig.toFile().mkdirs();
            Path upSavePath = upConfig
                    .resolve(defaultNewFilePath.getFileName());
            if (generationType.equals("regionservers")) {
                try {
                    FileWriter writerUp = new FileWriter(upSavePath.toFile());
                    for (String regionName : Config.getConf()
                            .getHBaseRegionServers()) {
                        writerUp.write(regionName + "\n");
                    }
                    writerUp.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return fileNameIdx++;
    }
}
