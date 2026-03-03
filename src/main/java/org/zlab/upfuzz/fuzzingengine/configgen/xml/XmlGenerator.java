package org.zlab.upfuzz.fuzzingengine.configgen.xml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.zlab.upfuzz.docker.DockerCluster;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.configgen.ConfigFileGenerator;

public class XmlGenerator extends ConfigFileGenerator {
    private static final Logger logger = LogManager
            .getLogger(XmlGenerator.class);

    public Map<String, String> configurations;
    public Map<String, String> newConfigurations;

    public Path defaultXMLPath;
    public Path defaultNewXMLPath;

    public XmlGenerator(
            Path defaultXMLPath,
            Path generateFolderPath) {
        super(generateFolderPath);
        this.defaultXMLPath = defaultXMLPath;
        configurations = parseXmlFile(defaultXMLPath);
        if (Config.getConf().system.equals("hdfs")
                && defaultXMLPath.getFileName().toString()
                        .equals("hdfs-site.xml")) {
            // logger.debug("set hdfs basic cluster config");
            hdfsClusterSetting(configurations);
        }
    }

    public XmlGenerator(
            Path defaultXMLPath, Path defaultNewXMLPath,
            Path generateFolderPath) {
        super(generateFolderPath);
        SetXMLPath(defaultXMLPath, defaultNewXMLPath);
    }

    public void SetXMLPath(Path defaultXMLPath, Path defaultNewXMLPath) {
        this.defaultXMLPath = defaultXMLPath;
        this.defaultNewXMLPath = defaultNewXMLPath;

        configurations = parseXmlFile(defaultXMLPath);
        newConfigurations = parseXmlFile(defaultNewXMLPath);
    }

    public void ProcessConfig() {
        if (Config.getConf().system.equals("hdfs")
                && defaultXMLPath.getFileName().toString()
                        .equals("hdfs-site.xml")) {
            // logger.info("set hdfs basic cluster config");
            hdfsClusterSetting(configurations);
            hdfsClusterSetting(newConfigurations);
        } else if (Config.getConf().system.equals("ozone")
                && defaultXMLPath.getFileName().toString()
                        .equals("ozone-site.xml")) {
            // logger.info("set hdfs basic cluster config");
            ozoneClusterSetting(configurations);
            ozoneClusterSetting(newConfigurations);
        } else if (Config.getConf().system.equals("ozone")
                && defaultXMLPath.getFileName().toString()
                        .equals("core-site.xml")) {
            // logger.info("set hdfs basic cluster config");
            ozoneNameClusterSetting(configurations);
            ozoneNameClusterSetting(newConfigurations);
        } else if (Config.getConf().system.equals("hbase")
                && defaultXMLPath.getFileName().toString()
                        .equals("hdfs-site.xml")) {
            // logger.debug("set hbase hdfs basic cluster config");
            HBasehdfsClusterSetting(configurations);
            HBasehdfsClusterSetting(newConfigurations);
        } else if (Config.getConf().system.equals("hbase")
                && defaultXMLPath.getFileName().toString()
                        .equals("core-site.xml")) {
            // logger.debug("set hbase hdfs namenode cluster config");
            HBasehdfsNameClusterSetting(configurations);
            HBasehdfsNameClusterSetting(newConfigurations);
        } else if (Config.getConf().system.equals("hbase")
                && defaultXMLPath.getFileName().toString()
                        .equals("hbase-site.xml")) {
            // logger.debug("set hbase cluster config");
            String originalVersionHbase = Config.getConf().originalVersion;
            String upgradedVersionHbase = Config.getConf().upgradedVersion;
            String[] versionPartsOriginal = originalVersionHbase
                    .substring(originalVersionHbase.indexOf("-") + 1)
                    .split("\\.");
            String[] versionPartsUpgraded = upgradedVersionHbase
                    .substring(upgradedVersionHbase.indexOf("-") + 1)
                    .split("\\.");
            int curMajorVersion = Integer.parseInt(versionPartsOriginal[0]);
            int curMinorVersion = Integer.parseInt(versionPartsOriginal[1]);

            int upMajorVersion = Integer.parseInt(versionPartsUpgraded[0]);
            int upMinorVersion = Integer.parseInt(versionPartsUpgraded[1]);

            HBaseClusterSetting(configurations);
            HBaseClusterSetting(newConfigurations);
            if (((curMajorVersion == 2 && curMinorVersion < 3)
                    || (curMajorVersion < 2))
                    || ((upMajorVersion == 2 && upMinorVersion < 3)
                            || (upMajorVersion < 2))) {
                HBaseClusterSetting(configurations, 1);
                HBaseClusterSetting(newConfigurations, 1);
            }
            // newConfigurations.put("hbase.procedure.upgrade-to-2-2", "true");
            // HBaseClusterSetting(configurations, 1);
            // HBaseClusterSetting(newConfigurations, 1);
        }
    }

    public void hdfsClusterSetting(Map<String, String> curConfigurations) {
        curConfigurations.put("dfs.namenode.name.dir",
                "/var/hadoop/data/nameNode");
        curConfigurations.put("dfs.datanode.data.dir",
                "/var/hadoop/data/dataNode");
        curConfigurations.put("dfs.replication", "2");
        curConfigurations.put(
                "dfs.namenode.datanode.registration.ip-hostname-check",
                "false");
        curConfigurations.put("dfs.secondary.http.address",
                "secondarynn:50090");
    }

    public void ozoneClusterSetting(Map<String, String> curConfigurations) {
        curConfigurations.put("ozone.om.metadata-dir", "/var/ozone/data/om");
        curConfigurations.put("ozone.scm.db.dirs", "/var/ozone/data/scm"); // No
                                                                           // replication
        curConfigurations.put("ozone.datanode.data.dir",
                "/var/ozone/data/dataNode"); // Single node replication
        // curConfigurations.put("ozone.replication", "3");
        curConfigurations.put("ozone.om.ratis.enable", "true");
        curConfigurations.put("ozone.scm.ha", "true");
        // curConfigurations.put("ozone.om.service.ids", "om-id-1");
        // curConfigurations.put("ozone.om.nodes", "om1");
        // curConfigurations.put("ozone.om.address.om-service-id.om1",
        // "om:9862");
        // curConfigurations.put("ozone.recon.db.dirs",
        // "/var/ozone/data/recon");
        // curConfigurations.put("ozone.scm.block.client.address",
        // "ozone-scm:9860");
        curConfigurations.put("ozone.scm.names", "ozone-scm");
        curConfigurations.put("ozone.scm.client.address", "ozone-scm:9860");
        curConfigurations.put("ozone.scm.datanode.address", "ozone-scm:9861");
        curConfigurations.put("ozone.om.address", "om:9862");
        curConfigurations.put(
                "ozone.scm.datanode.registration.ip-hostname-check",
                "false");
        curConfigurations.put("ozone.metadata.dirs",
                "/var/ozone/data/metadata");
    }

    public void ozoneNameClusterSetting(
            Map<String, String> curConfigurations) {
        curConfigurations.put("fs.defaultFS", "o3fs://bucket.volume.om");
        curConfigurations.put("ozone.om.address", "om:9862");
        curConfigurations.put("ozone.scm.names", "ozone-scm");
        curConfigurations.put("ozone.scm.client.address", "ozone-scm:9860");
    }

    public void HBasehdfsClusterSetting(Map<String, String> curConfigurations) {
        curConfigurations.put("dfs.namenode.name.dir",
                "/var/hadoop/data/nameNode");
        curConfigurations.put("dfs.datanode.data.dir",
                "/var/hadoop/data/dataNode");
    }

    public void HBasehdfsNameClusterSetting(
            Map<String, String> curConfigurations) {
        curConfigurations.put("fs.default.name",
                "hdfs://master:8020");
    }

    public void HBaseClusterSetting(
            Map<String, String> curConfigurations) {
        curConfigurations.put("hbase.cluster.distributed",
                "true");
        curConfigurations.put("hbase.rootdir",
                "hdfs://master:8020/hbase");
        curConfigurations.put("hbase.zookeeper.property.dataDir",
                "/usr/local/zookeeper");

        if (Config.getConf().reproduce_HBASE22503) {
            curConfigurations.put("hbase.coprocessor.master.classes",
                    "org.apache.hadoop.hbase.security.access.AccessController");
            curConfigurations.put("hbase.coprocessor.region.classes",
                    "org.apache.hadoop.hbase.security.access.AccessController");
            curConfigurations.put("hbase.security.authorization", "true");
        }
        // curConfigurations.put("zookeeper.snapshot.trust.empty",
        // "true");
        // curConfigurations.put("hbase.unsafe.stream.capability.enforce",
        // "false");
        // curConfigurations.put("hbase.procedure.upgrade-to-2-2", "true");
        curConfigurations.put("hbase.zookeeper.quorum",
                Config.getConf().getHBaseZookeeperQuorum());
        // curConfigurations.put("hbase.rpc.engine",
        // "org.apache.hadoop.hbase.ipc.SecureRpcEngine");
        // curConfigurations.put("hbase.procedure.store.wal.use.hsync",
        // "false");
        // Enable quota setting
        if (Config.getConf().enableQuota)
            curConfigurations.put("hbase.quota.enabled",
                    "true");
    }

    public void HBaseClusterSetting(Map<String, String> curConfigurations,
            int version) {
        curConfigurations.put("zookeeper.snapshot.trust.empty", "true");
    }

    public static Map<String, String> parseXmlFile(Path filePath) {
        Map<String, String> curConfigurations = new LinkedHashMap<>();

        SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
        try {
            SAXParser saxParser = saxParserFactory.newSAXParser();
            PropertyHandler handler = new PropertyHandler();
            saxParser.parse(new File(String.valueOf(filePath)), handler);
            // Get Properties list
            List<Property> propertyList = handler.getPropertyList();
            // print user information
            if (propertyList != null) {
                for (Property property : propertyList) {
                    String name = property.getName();
                    String value = property.getValue();
                    if ((name != null) && (value != null)) {
                        curConfigurations.put(name, value);
                    }
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        return curConfigurations;
    }

    public void generateXmlFile(Map<String, String> key2val, Path path) {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory
                .newInstance();
        DocumentBuilder docBuilder;
        try {
            docBuilder = docFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        Document doc = docBuilder.newDocument();

        Element rootElement = doc.createElement("configuration");
        doc.appendChild(rootElement);

        for (String key : key2val.keySet()) {
            Element property = doc.createElement("property");
            rootElement.appendChild(property);
            Element nameEle = doc.createElement("name");
            nameEle.setTextContent(key);
            Element valueEle = doc.createElement("value");
            valueEle.setTextContent(key2val.get(key));
            property.appendChild(nameEle);
            property.appendChild(valueEle);
        }
        doc.setXmlStandalone(true);

        // write dom document to a file
        try (FileOutputStream output = new FileOutputStream(path.toFile())) {
            writeXml(doc, output);
        } catch (IOException | TransformerException e) {
            e.printStackTrace();
        }
    }

    // write doc to output stream
    private static void writeXml(Document doc, OutputStream output)
            throws TransformerException {

        TransformerFactory transformerFactory = TransformerFactory
                .newInstance();
        Transformer transformer = transformerFactory.newTransformer();

        // pretty print XML
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(output);
        transformer.transform(source, result);
    }

    @Override
    public int generate(Map<String, String> key2vals,
            Map<String, String> key2type,
            Map<String, String> newkey2vals,
            Map<String, String> newkey2type) {
        ProcessConfig();
        // clone a configuration map, and dump it
        Path savePath = generateFolderPath
                .resolve(String.format("test%d", fileNameIdx));
        Path oriConfig = savePath.resolve("oriconfig");
        Path upConfig = savePath.resolve("upconfig");
        oriConfig.toFile().mkdirs();
        upConfig.toFile().mkdirs();

        Path oriSavePath = oriConfig.resolve(defaultXMLPath.getFileName());
        Path upSavePath = upConfig.resolve(defaultNewXMLPath.getFileName());

        key2vals.putAll(configurations);
        newkey2vals.putAll(newConfigurations);

        generateXmlFile(key2vals, oriSavePath);
        generateXmlFile(newkey2vals, upSavePath);

        return fileNameIdx++;
    }

    @Override
    public int generate(Map<String, String> key2vals,
            Map<String, String> key2type) {
        Path savePath = generateFolderPath
                .resolve(String.format("test%d", fileNameIdx));
        Path oriConfig = savePath.resolve("oriconfig");
        oriConfig.toFile().mkdirs();
        Path oriSavePath = oriConfig.resolve(defaultXMLPath.getFileName());
        key2vals.putAll(configurations);
        generateXmlFile(key2vals, oriSavePath);
        return fileNameIdx++;
    }

    @Override
    public int generate(boolean isUpgrade) {
        ProcessConfig();
        // clone a configuration map, and dump it
        Path savePath = generateFolderPath
                .resolve(String.format("test%d", fileNameIdx));
        Path oriConfig = savePath.resolve("oriconfig");
        oriConfig.toFile().mkdirs();
        Path oriSavePath = oriConfig.resolve(defaultXMLPath.getFileName());
        generateXmlFile(configurations, oriSavePath);
        if (isUpgrade) {
            Path upConfig = savePath.resolve("upconfig");
            upConfig.toFile().mkdirs();
            Path upSavePath = upConfig.resolve(defaultNewXMLPath.getFileName());
            generateXmlFile(newConfigurations, upSavePath);
        }
        return fileNameIdx++;
    }

}
