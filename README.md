# HiveMQ TDengine JDSL 插件
让我们做些有趣的事情比如自定义 json 模板。但这一切都是实验性的创新！

## 目标
将 publish 到 HiveMQ 的数据通过自定义的 json 模板和存储策略进行智能化存储,只需要定义好配置文件即可将所想数据存入 TDengine

## 使用项目
1. jdsl 根项目 [https://github.com/huskar-t/jdsl](https://github.com/huskar-t/jdsl): golang 编写的 json 模板解析,提供理解json模板和读取数据生成 TDengine sql方法。可生成全平台动态库
2. jdsl java包装器 [https://github.com/huskar-t/jdsl-jna](https://github.com/huskar-t/jdsl-jna): 将 golang 生成的动态库加一层 java jna 封装提供给 java 使用

## jdsl 模板

### 模板定义
json 中的不确定值(模板变量)使用美元符包裹 如 $gateway$

### 配置解析
```go
type Config struct {
	DBName          string            `json:"dbName"`
	STableName      string            `json:"sTableName"`
	TableName       string            `json:"tableName"`
	Keep            int               `json:"keep"`
	TagsMap         map[string]string `json:"tagsMap"`
	PayloadTemplate string            `json:"payloadTemplate"`
	Value           string            `json:"value"`
}
```
> * dbName:  TDengine 数据库名
> * sTableName:  TDengine 超级表名
> * tableName:  TDengine 表名(可为模板变量)
> * keep: 数据保存天数 
> * tagsMap: 最终数据的分组依据 键为超级表的tag定义,值可以为模板变量也可以为常量字符串
> * payloadTemplate: json 模板
> * value: 要存储的数据(必须为模板变量)

### 规则
> * 为避免表名为纯数字,默认添加前缀 "t_"
> * 暂不支持自定义写入时间
> * tag 的值只能为字符串,不可使用数组
> * value 必须为模板变量,支持 数字型 字符串 布尔值
> * tag 不能包含 value 的模板变量
> * 字符串限制最大长度64
> * 如果模板变量在数组中,
>   * 想将数组中所有元素作为模板变量的值,则定义 [模板变量],
>   * 如果想指定所在元素的下标,则使用$temp$(不一定使用 temp 只要使用一个不关心模板变量即可) 占位,如关心下标为3的值 ["$temp$","$temp$","$temp$","模板变量"]

### 样例
> 模板
> ```json
> {
>   "dbName": "db",
>   "sTableName": "stb",
>   "tableName": "$pointName$",
>   "keep": 365,
>   "tagsMap": {
>     "device": "$device$"
>   },
>   "payloadTemplate": "{\"device\":\"$device$\",\"point\":{\"pointName\":\"$pointName$\",\"value\":[\"$value$\"]}}",
>   "value": "$value$"
> }
> ```
> 测试实时消息
> ```json
> {
>   "device": "d1",
>   "point": {
>     "pointName": "sunshine",
>     "value": [
>       92,
>       93,
>       94
>     ]
>   }
> }
> ```
> 生成语句
> ```sql
> CREATE DATABASE IF NOT EXISTS db KEEP 365
> CREATE TABLE IF NOT EXISTS db.stb_double (ts timestamp, value float) TAGS (device binary(64))
> CREATE TABLE IF NOT EXISTS db.stb_string (ts timestamp, value NCHAR(64)) TAGS (device binary(64))
> IMPORT INTO db.t_sunshine USING db.stb_double TAGS ("d1") VALUES (now,92)
> IMPORT INTO db.t_sunshine USING db.stb_double TAGS ("d1") VALUES (now,93)
> IMPORT INTO db.t_sunshine USING db.stb_double TAGS ("d1") VALUES (now,94)
> ```
> 模板
> ```json
> {
>   "$gatewayID$": {
>     "$deviceID$": {
>       "$pointName$": "$value$"
>     }
>   }
> }
> ```
> 测试实时消息
> ```json
> {
>   "g1": {
>     "d1": {
>       "p1": "1"
>     },
>     "d2": {
>       "p2": 1
>     }
>   }
> }
> ```
> 生成语句
> ```sql
> CREATE DATABASE IF NOT EXISTS db KEEP 365
> CREATE TABLE IF NOT EXISTS db.stb_double (ts timestamp, value float) TAGS (device binary(64),gateway binary(64))
> CREATE TABLE IF NOT EXISTS db.stb_string (ts timestamp, value NCHAR(64)) TAGS (device binary(64),gateway binary(64))
> IMPORT INTO db.t_p1 USING db.stb_string TAGS ("d1","g1") VALUES (now,'1')
> IMPORT INTO db.t_p2 USING db.stb_double TAGS ("d2","g1") VALUES (now,1)
> IMPORT INTO db.t_p1 USING db.stb_string TAGS ("d1","g2") VALUES (now,'2')
> IMPORT INTO db.t_p2 USING db.stb_double TAGS ("d2","g2") VALUES (now,2)
> ```
> 
> 模板
> ```json
> {
>   "dbName": "db",
>   "sTableName": "stb",
>   "tableName": "zone",
>   "keep": 365,
>   "tagsMap": {
>     "type": "$ver$"
>   },
>   "payloadTemplate": "{\"$ver$\":\"$value$\"}",
>   "value": "$value$"
> }
> ```
> 测试实时消息
> ```json
> {
>   "temperature": "15",
>   "humidity": "17"
> }
> ```
> 生成语句
> ```sql
> CREATE DATABASE IF NOT EXISTS db KEEP 365
> CREATE TABLE IF NOT EXISTS db.stb_double (ts timestamp, value float) TAGS (type binary(64))
> CREATE TABLE IF NOT EXISTS db.stb_string (ts timestamp, value NCHAR(64)) TAGS (type binary(64))
> IMPORT INTO db.t_zone USING db.stb_string TAGS ("temperature") VALUES (now,'15')
> IMPORT INTO db.t_zone USING db.stb_string TAGS ("humidity") VALUES (now,'17')
> ```

更多有趣的格式自由发挥

## 架构
> * TDengine.java 负责处理数据库连接 创建库表 写入数据
> * TDengineExtension.java 为插件主入口 
>  * extensionStart 方法在插件启动时调用
>    * 传入 tdengine.xml (tdengine配置文件)文件位置给 TDengine 进行创建数据库连接和初始化库表
>    * 注册 TDengineInterceptor
>  * extensionStop 方法在插件结束生命周期时调用
>    * 调用 tdengine.close 方法断开 SDK 连接
> * TDengineInterceptor.java 拦截器实现
>  * onInboundPublish 方法在消息 publish 时触发
>    * 异步调用 tdengine.saveData 方法写入 TDengine 数据库

## 编译步骤
```shell script
mvn clean
mvn package
```

## 部署方法

### TDengine 
见官方文档: [https://www.taosdata.com/cn/getting-started/](https://www.taosdata.com/cn/getting-started/)

### HiveMQ
见官方文档: [https://www.hivemq.com/docs/hivemq/4.4/user-guide/install-hivemq.html](https://www.hivemq.com/docs/hivemq/4.4/user-guide/install-hivemq.html)

### 插件部署
> 1. 将打包好的压缩包如: tdengine-1.0-SNAPSHOT-distribution.zip 解压到 HiveMQ 目录的 extensions 文件夹下  
> 2. 修改插件包内的 tdengine.xml 配置文件为实际使用的数据库信息  
> 3. 修改插件包内的 template.json 格式如下 keep 为数据保留时间
```json
{
  "topic名(可包含模板变量)": {
    "dbName": "创建数据库名",
    "sTableName": "超级表名(相同 dbName 下的 sTableName 不可相同,因为tag名称不同而超级表名相同时后面定义的超级表将被忽略)",
    "tableName": "表名(可为模板变量)",
    "keep": 365,
    "tagsMap": {
      "tag名称": "tag值"
    },
    "payloadTemplate": "json模板",
    "value": "要存的值(必须为变量模板)"
  }
}
```

### 性能测试
由于可以自定义json格式,针对不同消息解析时间也不相同,在使用时自行测试

### 注意事项
> 不支持 json 中出现中文
> 出现如下错误时 执行 ```mvn clean```
```shell script
Could not find artifact com.huskar_t:jdsl:pom:1.0
```

> tdengine.xml 文件的 ip 属性如果指定 type 为 sdk 则应使用域名或修改 host 不可直接使用 ip 地址 详情见 [https://www.taosdata.com/blog/2020/09/11/1824.html](https://www.taosdata.com/blog/2020/09/11/1824.html)

> TDengine 的 FAQ: [https://www.taosdata.com/cn/documentation/faq/](https://www.taosdata.com/cn/documentation/faq/)
> 项目中的 libjdsl.so 和 jdsl.dll 运行平台分别为 linux amd64 和 windows amd64 如果需要其他平台动态库请到 jsdl 根项目中下载替换