package com.huskar_t;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.huskar_t.jdsl.CParseResult;
import com.huskar_t.jdsl.Config;
import com.huskar_t.jdsl.JDSL;
import com.huskar_t.jdsl.ParseResult;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link com.huskar_t.TDengineJDSL}
 * Processing tdengine related operations, including initializing connections and writing data
 * the ip must be the username when connecting to tdengine with sdk
 * FAQ ( https://github.com/taosdata/TDengine/blob/develop/documentation20/webdocs/markdowndocs/faq-ch.md )
 */
public class TDengineJDSL {
    private static final @NotNull Logger log = LoggerFactory.getLogger(com.huskar_t.TDengineJDSL.class);
    private static final int SUCCESS_CODE = 200;
    private String ip;
    private String port;
    private String username;
    private String password;
    private String type;
    private Integer maxlength;
    private Connection conn;
    private String token;
    private String url;
    private Statement stmt = null;
    private final String basePath;
    private Map<String, ParseResult> templates;
    private final ArrayList<String[]> topics;
    private final Cache<String, TopicMath[]> cache;
    private JDSL jdsl;
    private final ArrayList<String> createDBSQL = new ArrayList<>();
    private final ArrayList<String> createTableSQL = new ArrayList<>();
    private final Lock lock=new ReentrantLock();
    private CloseableHttpClient client;

    public TDengineJDSL(String basePath) throws Exception {
        this.basePath = basePath;
        String configPath = basePath + File.separator + "tdengine.xml";
        File file = new File(configPath);
        SAXReader read = new SAXReader();
        org.dom4j.Document doc = read.read(file);
        Element root = doc.getRootElement();
        this.setType(root.elementTextTrim("type"));
        this.setIp(root.elementTextTrim("ip"));
        this.setPort(root.elementTextTrim("port"));
        this.setUsername(root.elementTextTrim("username"));
        this.setPassword(root.elementTextTrim("password"));
        this.setMaxlength(Integer.valueOf(root.elementTextTrim("maxlength")));
        this.topics = new ArrayList<>();
        this.cache = Caffeine.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES).build();
        this.templates = new HashMap<>();
        this.readTemplate();
        this.client = HttpClients.createDefault();
    }

    private void readTemplate() throws Exception {
//        使用 JDSL 解析配置
        this.jdsl = new JDSL(this.basePath);
        String s = readJsonFile(this.basePath + File.separator + "template.json");
        JSONObject template = JSON.parseObject(s);
        Iterator<String> topics = template.keySet().iterator();
        while (topics.hasNext()) {
            String topic = topics.next();
            Config templateConfig = template.getObject(topic, Config.class);
//            todo 时间来不及了不用树实现了
            String[] topicItems = topic.split("/");
            for (int i = 0; i < topicItems.length; i++) {
                if (topicItems[i].equals("#") && i != topicItems.length - 1) {
                    throw new Exception("topics defined error: " + topic);
                }
            }
            CParseResult result = jdsl.ParseJson(templateConfig);
            this.createDBSQL.add(result.sql.dbSql);
            this.createTableSQL.add(result.sql.tableFloatSql);
            this.createTableSQL.add(result.sql.tableStringSql);
            this.templates.put(topic, result.result);
            this.topics.add(topicItems);
        }
    }

    private TopicMath[] matchedTopics(String topic) {
//        设置一级缓存
        TopicMath[] result = this.cache.getIfPresent(topic);
        if (result != null) {
            return result;
        }
        String[] topicItems = topic.split("/");
        ArrayList<TopicMath> matchedResult = new ArrayList<>();
        for (String[] templateTopic :
                this.topics) {
            if (topicItems.length < templateTopic.length) {
                continue;
            } else if (topicItems.length > templateTopic.length && !templateTopic[templateTopic.length - 1].equals("#")) {
                continue;
            }
            TopicMath matchResult = new TopicMath();
            boolean matched = true;
            for (int i = 0; i < templateTopic.length; i++) {
                if (!templateTopic[i].equals("+")) {
                    if (templateTopic[i].equals("*")) {
                        break;
                    } else if (isTemplate(templateTopic[i])) {
                        matchResult.templateValue.put(templateTopic[i], topicItems[i]);
                    } else {
                        if (!templateTopic[i].equals(topicItems[i])) {
                            matched = false;
                            break;
                        }
                    }
                }
            }
            if (matched) {
                matchResult.topic = StringUtils.join(templateTopic, "/");
                matchedResult.add(matchResult);
            }
        }
        TopicMath[] r =  matchedResult.toArray(new TopicMath[matchedResult.size()]);
        this.cache.put(topic, r);
        return r;
    }

    private Boolean isTemplate(String s) {
        return s.length() > 2 && s.charAt(0) == '$' && s.charAt(s.length() - 1) == '$';
    }

    public static String readJsonFile(String fileName) {
        String jsonStr = "";
        try {
            File jsonFile = new File(fileName);
            FileReader fileReader = new FileReader(jsonFile);
            Reader reader = new InputStreamReader(new FileInputStream(jsonFile), StandardCharsets.UTF_8);
            int ch = 0;
            StringBuilder sb = new StringBuilder();
            while ((ch = reader.read()) != -1) {
                sb.append((char) ch);
            }
            fileReader.close();
            reader.close();
            jsonStr = sb.toString();
            return jsonStr;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }


    public boolean connect() {
        switch (getType()) {
            case "http":
                this.url = String.format("http://%s:%s/rest/sql", this.getIp(), this.getPort());
                final Base64.Encoder encoder = Base64.getEncoder();
                final byte[] textByte = String.format("%s:%s", this.getUsername(), this.getPassword()).getBytes(StandardCharsets.UTF_8);
                this.token = "Basic " + encoder.encodeToString(textByte);
                return this.doConnectHttp();
            case "sdk":
                return this.doConnectSDK();
            default:
                this.setType("http");
                log.error("tdengine connect type unsupported using http");
                return this.doConnectHttp();
        }
    }

    private boolean doConnectHttp() {
        return httpCreateDBAndTable();
    }

    private boolean doConnectSDK() {
        try {
            Class.forName("com.taosdata.jdbc.TSDBDriver");
        } catch (ClassNotFoundException e) {
            log.error("get tdengine class error", e);
            return false;
        }
        String connectStr = String.format("jdbc:TAOS://%s:%s/?user=%s&password=%s", this.getIp(), this.getPort(), this.getUsername(), this.getPassword());
        try {
            this.conn = DriverManager.getConnection(connectStr);
        } catch (SQLException e) {
            log.error("connect to tdengine false", e);
        }
        return sdkCreateDBAndTable();
    }

    private boolean sdkCreateDBAndTable() {
        if (this.conn != null) {
            try {
                this.stmt = this.conn.createStatement();
            } catch (SQLException e) {
                log.error("tdengine create statement error", e);
                return false;
            }
            if (this.stmt == null) {
                log.error("tdengine statement is null");
                return false;
            }
            // create database
            try {
                for (String value : this.createDBSQL) {
                    this.stmt.executeUpdate(value);
                }
            } catch (SQLException e) {
                log.error("tdengine create database error", e);
                return false;
            }
            // create table
            try {
                for (String s : this.createTableSQL) {
                    this.stmt.executeUpdate(s);
                }
            } catch (SQLException e) {
                log.error("tdengine create table error", e);
                return false;
            }
        }
        return true;
    }

    private boolean httpCreateDBAndTable() {
        for (String value : this.createDBSQL) {
            log.error(value);
            JSONObject createDBResult = doPost(value);
            if (createDBResult == null) {
                log.error("http create db error");
                return false;
            }
        }
        for (String s : this.createTableSQL) {
            log.error(s);
            JSONObject createTableResult = doPost(s);
            if (createTableResult == null) {
                log.error("http create table error");
                return false;
            }
        }
        return true;
    }

    public void close() {
        if (this.stmt != null) {
            try {
                this.stmt.close();
            } catch (SQLException e) {
                log.warn("close tdengine statement error", e);
            }
        }
        if (this.conn != null) {
            try {
                this.conn.close();
            } catch (SQLException e) {
                log.warn("close tdengine connect error", e);
            }
        }
        if (this.client != null) {
            try {
                this.client.close();
            } catch (IOException e) {
                log.error("close client error", e);
            }
        }
    }

    private JSONObject doPost(String sql) {
        JSONObject jsonObject;
        CloseableHttpResponse response = null;
        try {
            HttpPost post = new HttpPost(this.url);
            StringEntity entity = new StringEntity(sql, "UTF-8");
            post.setEntity(entity);
            post.setHeader(new BasicHeader("Content-Type", "application/json"));
            post.setHeader(new BasicHeader("Authorization", this.token));
            post.setHeader(new BasicHeader("Accept", "text/plain;charset=utf-8"));
            response = this.client.execute(post);
            int statusCode = response.getStatusLine().getStatusCode();
            String result = EntityUtils.toString(response.getEntity(), "UTF-8");
            if (SUCCESS_CODE == statusCode) {
                try {
                    jsonObject = JSONObject.parseObject(result);
                    return jsonObject;
                } catch (Exception e) {
                    log.error("parse json error", e);
                    return null;
                }
            } else {
                log.error("HttpClientService errorMsg：{}", result);
                return null;
            }
        } catch (Exception e) {
            log.error("Http Exception：", e);
            return null;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    log.error("close response error", e);
                }
            }

        }
    }

    public boolean saveData(String topic, String payload) {
        TopicMath[] templateTopics = this.matchedTopics(topic);
        if (templateTopics == null || templateTopics.length == 0) {
            return false;
        }
        for (TopicMath templateTopic : templateTopics) {
            ParseResult parseResult = this.templates.get(templateTopic.topic);
            try {
                String[] sqlList = this.jdsl.Read(parseResult, payload, templateTopic.templateValue);
                switch (this.getType()) {
                    case "http":
                        for (String sql : sqlList) {
                            log.error(sql);
                            JSONObject result = this.doPost(sql);
                            if (result == null) {
                                return false;
                            }
                            String status = result.getString("status");
                            if (!status.equals("succ")) {
                                return false;
                            }
                        }
                        break;
                    case "sdk":
                        lock.lock();
                        try {
                            for (String sql : sqlList) {
                                try {
                                    this.stmt.executeUpdate(sql);
                                } catch (SQLException e) {
                                    log.error("save data error", e);
                                    return false;
                                }
                            }
                        }finally {
                            lock.unlock();
                        }
                        break;
                    default:
                        return false;
                }
            } catch (Exception e) {
                log.error("jdsl read error", e);
                return false;
            }

        }
        return true;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) throws Exception {
        if (ip.equals("")) {
            throw new Exception("ip is required");
        }
        if (ip.startsWith("http://")) {
            ip = ip.substring(7);
        }
        this.ip = ip;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (username.equals("")) {
            username = "root";
        }
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        if (password.equals("")) {
            password = "taosdata";
        }
        this.password = password;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        if (port.equals("")) {
            if ("sdk".equals(this.type)) {
                port = "6030";
            } else {
                port = "6041";
            }
        }
        this.port = port;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        if (type.equals("")) {
            type = "http";
        }
        this.type = type;
    }


    public Integer getMaxlength() {
        return maxlength;
    }

    public void setMaxlength(Integer maxlength) {
        if (maxlength < 64) {
            maxlength = 64;
        }
        this.maxlength = maxlength;
    }
}
