package org.zlab.upfuzz.hdfs;

import org.zlab.upfuzz.fuzzingengine.configgen.ConfigGen;
import org.zlab.upfuzz.fuzzingengine.configgen.PlainTextGenerator;
import org.zlab.upfuzz.fuzzingengine.configgen.xml.XmlGenerator;

import java.nio.file.Path;
import java.nio.file.Files;

public class HdfsConfigGen extends ConfigGen {
    private Path resolveHdfsSiteXml(Path versionPath) {
        Path packagedPath = versionPath.resolve("etc/hadoop/hdfs-site.xml");
        if (Files.exists(packagedPath)) {
            return packagedPath;
        }
        // Source tarballs keep default configs under hadoop-hdfs-project.
        Path sourcePath = versionPath.resolve(
                "hadoop-hdfs-project/hadoop-hdfs/src/main/conf/hdfs-site.xml");
        return sourcePath;
    }

    @Override
    public void updateConfigBlackList() {
    }

    @Override
    public void initUpgradeFileGenerator() {
        Path defaultConfigPath = resolveHdfsSiteXml(oldVersionPath);
        Path defaultNewConfigPath = resolveHdfsSiteXml(newVersionPath);
        configFileGenerator = new XmlGenerator[1];
        configFileGenerator[0] = new XmlGenerator(defaultConfigPath,
                defaultNewConfigPath, generateFolderPath);
        extraGenerator = new PlainTextGenerator[0];
    }

    @Override
    public void initSingleFileGenerator() {
        Path defaultConfigPath = resolveHdfsSiteXml(oldVersionPath);
        configFileGenerator = new XmlGenerator[1];
        configFileGenerator[0] = new XmlGenerator(defaultConfigPath,
                generateFolderPath);
        extraGenerator = new PlainTextGenerator[0];
    }
}
