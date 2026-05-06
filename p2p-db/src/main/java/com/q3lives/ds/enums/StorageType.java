
package com.q3lives.ds.enums;



import java.util.*;

/**
 * 数据库存储类型枚举
 * 
 * 涵盖了现代数据库系统中主要的数据存储格式和组织方式
 * 
 * @author Database Storage Research Team
 * @version 1.0
 */
public enum StorageType {
    
    // ==================== 基础存储类型 ====================
    
    /**
     * 行存储 (Row-Oriented Storage / NSM - N-ary Storage Model)
     * 
     * 特点：
     * - 将一行的所有列数据连续存储
     * - 适合OLTP场景，事务处理
     * - 插入、更新、删除效率高
     * - 全行查询性能好
     * 
     * 典型数据库：MySQL InnoDB, PostgreSQL, Oracle, SQL Server
     * 
     * 存储示例：
     * [id=1, name="Alice", age=25, city="NYC"] [id=2, name="Bob", age=30, city="LA"]
     */
    ROW_STORE(
        "行存储",
        "Row-Oriented Storage",
        StorageCategory.BASIC,
        Arrays.asList(
            "适合事务处理(OLTP)",
            "全行读写效率高",
            "随机访问友好",
            "支持高并发写入"
        ),
        Arrays.asList(
            "分析查询效率低",
            "列级聚合性能差",
            "压缩率较低",
            "缓存利用率低"
        ),
        Arrays.asList("MySQL InnoDB", "PostgreSQL", "Oracle", "SQL Server")
    ),
    
    /**
     * 列存储 (Column-Oriented Storage / DSM - Decomposition Storage Model)
     * 
     * 特点：
     * - 将同一列的数据连续存储
     * - 适合OLAP场景，分析查询
     * - 列级聚合、扫描效率高
     * - 压缩率高（同类型数据）
     * 
     * 典型数据库：ClickHouse, Apache Parquet, Apache ORC, Vertica
     * 
     * 存储示例：
     * id列: [1, 2, 3, 4]
     * name列: ["Alice", "Bob", "Charlie", "David"]
     * age列: [25, 30, 35, 40]
     */
    COLUMN_STORE(
        "列存储",
        "Column-Oriented Storage",
        StorageCategory.BASIC,
        Arrays.asList(
            "分析查询效率高",
            "列级聚合性能好",
            "压缩率高",
            "IO效率高（只读需要的列）"
        ),
        Arrays.asList(
            "单行插入效率低",
            "更新操作复杂",
            "全行查询性能差",
            "事务处理能力弱"
        ),
        Arrays.asList("ClickHouse", "Apache Parquet", "Vertica", "Apache ORC", "Amazon Redshift")
    ),
    
    
    // ==================== 混合存储类型 ==================== 
    
    /**
     * 复合列存储 (Fixed-length Composite Columns-Oriented Storage / DSM - Decomposition Storage Model)
     * 
     * 特点：
     * - 将多个定长列的数据对齐组合存储
     * - 适合OLAP场景，分析查询
     * - 列级聚合、扫描效率高
     * - 压缩率高（同类型数据）
     * - 结合行存储和列存储优点
     * - 缓存友好，减少CPU cache miss
     * - 适合混合负载
     * 
     * 存储示例（每个页内）：-> 应用编译器对齐技术。
     * Page 1: [id列: 1,2,3] [name列: "A","B","C"] [age列: 25,30,35]
     * Page 2: [id列: 4,5,6] [name列: "D","E","F"] [age列: 40,45,50]
     */
    COMPOSITE_COLUMN_STORE(
        "固定长度复合列存储",
        "Partition Attributes Across",
        StorageCategory.HYBRID,
        Arrays.asList(
            "缓存友好",
            "减少CPU cache miss",
            "兼顾行列存储优点",
            "适合混合负载"
        ),
        Arrays.asList(
            "实现复杂",
            "页大小需要优化",
            "跨页查询可能低效"
        ),
        Arrays.asList("MonetDB", "某些内存数据库")
    ),
    
    /**
     * 混合行列存储 (Hybrid Transactional/Analytical Processing)
     * 
     * 特点：
     * - 同时维护行存储和列存储
     * - 热数据行存储，冷数据列存储
     * - 支持HTAP场景
     * 
     * 典型数据库：TiDB, SAP HANA, Oracle Database In-Memory
     */
    HYBRID_STORE(
        "混合行列存储",
        "Hybrid Row-Column Storage",
        StorageCategory.HYBRID,
        Arrays.asList(
            "同时支持OLTP和OLAP",
            "灵活的数据组织",
            "实时分析能力",
            "资源利用率高"
        ),
        Arrays.asList(
            "存储开销大",
            "数据同步复杂",
            "维护成本高"
        ),
        Arrays.asList("TiDB", "SAP HANA", "Oracle In-Memory", "SQL Server ColumnStore")
    ),
    
    // ==================== 高级存储类型 ====================
    
    /**
     * 日志结构合并树 (LSM-Tree - Log-Structured Merge-Tree)
     * 
     * 特点：
     * - 写入优化，顺序写入
     * - 分层存储，定期合并
     * - 适合写多读少场景
     * 
     * 典型数据库：LevelDB, RocksDB, Cassandra, HBase
     */
    LSM_TREE(
        "日志结构合并树",
        "Log-Structured Merge-Tree",
        StorageCategory.ADVANCED,
        Arrays.asList(
            "写入性能极高",
            "顺序IO友好",
            "支持高吞吐写入",
            "空间利用率高"
        ),
        Arrays.asList(
            "读放大问题",
            "写放大问题",
            "合并开销大",
            "查询延迟不稳定"
        ),
        Arrays.asList("RocksDB", "LevelDB", "Cassandra", "HBase", "ScyllaDB")
    ),
    
    /**
     * B+树存储 (B+ Tree Storage)
     * 
     * 特点：
     * - 平衡树结构
     * - 支持范围查询
     * - 适合随机读写
     * 
     * 典型数据库：MySQL InnoDB, PostgreSQL
     */
    B_PLUS_TREE(
        "B+树存储",
        "B+ Tree Storage",
        StorageCategory.ADVANCED,
        Arrays.asList(
            "范围查询高效",
            "随机访问性能好",
            "支持事务",
            "成熟稳定"
        ),
        Arrays.asList(
            "写入需要维护树结构",
            "空间碎片问题",
            "大批量写入效率低"
        ),
        Arrays.asList("MySQL InnoDB", "PostgreSQL", "SQLite")
    ),
    
    /**
     * 时序存储 (Time-Series Storage)
     * 
     * 特点：
     * - 针对时间序列数据优化
     * - 时间分区存储
     * - 高效的时间范围查询
     * - 数据压缩和降采样
     * 
     * 典型数据库：InfluxDB, TimescaleDB, OpenTSDB
     */
    TIME_SERIES_STORE(
        "时序存储",
        "Time-Series Storage",
        StorageCategory.SPECIALIZED,
        Arrays.asList(
            "时间查询极快",
            "高压缩率",
            "支持降采样",
            "写入吞吐量高"
        ),
        Arrays.asList(
            "非时序查询弱",
            "更新困难",
            "删除效率低"
        ),
        Arrays.asList("InfluxDB", "TimescaleDB", "OpenTSDB", "Prometheus")
    ),
    
    /**
     * 文档存储 (Document Storage)
     * 
     * 特点：
     * - 存储JSON/BSON文档
     * - 灵活的Schema
     * - 嵌套数据支持
     * 
     * 典型数据库：MongoDB, CouchDB, Elasticsearch
     */
    DOCUMENT_STORE(
        "文档存储",
        "Document Storage",
        StorageCategory.SPECIALIZED,
        Arrays.asList(
            "Schema灵活",
            "嵌套数据友好",
            "开发效率高",
            "水平扩展容易"
        ),
        Arrays.asList(
            "关联查询弱",
            "事务支持有限",
            "数据冗余",
            "存储开销大"
        ),
        Arrays.asList("MongoDB", "CouchDB", "Elasticsearch", "Couchbase")
    ),
    
    /**
     * 键值存储 (Key-Value Storage)
     * 
     * 特点：
     * - 简单的键值对
     * - 极高的读写性能
     * - 水平扩展性好
     * 
     * 典型数据库：Redis, Memcached, DynamoDB
     */
    KEY_VALUE_STORE(
        "键值存储",
        "Key-Value Storage",
        StorageCategory.SPECIALIZED,
        Arrays.asList(
            "性能极高",
            "实现简单",
            "扩展性好",
            "低延迟"
        ),
        Arrays.asList(
            "查询能力有限",
            "无复杂查询",
            "数据关系弱"
        ),
        Arrays.asList("Redis", "Memcached", "DynamoDB", "Riak")
    ),
    
    /**
     * 图存储 (Graph Storage)
     * 
     * 特点：
     * - 针对图数据优化
     * - 关系遍历高效
     * - 支持复杂图查询
     * 
     * 典型数据库：Neo4j, JanusGraph, ArangoDB
     */
    GRAPH_STORE(
        "图存储",
        "Graph Storage",
        StorageCategory.SPECIALIZED,
        Arrays.asList(
            "关系查询高效",
            "图遍历性能好",
            "支持复杂图算法",
            "关系建模自然"
        ),
        Arrays.asList(
            "大规模图性能下降",
            "分布式困难",
            "存储开销大"
        ),
        Arrays.asList("Neo4j", "JanusGraph", "ArangoDB", "TigerGraph")
    ),
    
    // ==================== 内存存储类型 ====================
    
    /**
     * 内存行存储 (In-Memory Row Storage)
     * 
     * 特点：
     * - 全内存存储
     * - 极低延迟
     * - 适合实时处理
     * 
     * 典型数据库：Redis, Memcached, VoltDB
     */
    IN_MEMORY_ROW(
        "内存行存储",
        "In-Memory Row Storage",
        StorageCategory.IN_MEMORY,
        Arrays.asList(
            "延迟极低",
            "吞吐量极高",
            "实时处理能力强"
        ),
        Arrays.asList(
            "成本高",
            "容量受限",
            "持久化复杂",
            "数据丢失风险"
        ),
        Arrays.asList("Redis", "VoltDB", "MemSQL")
    ),
    
    /**
     * 内存列存储 (In-Memory Column Storage)
     * 
     * 特点：
     * - 内存中的列式存储
     * - 实时分析能力
     * - 高压缩率
     * 
     * 典型数据库：SAP HANA, Apache Ignite
     */
    IN_MEMORY_COLUMN(
        "内存列存储",
        "In-Memory Column Storage",
        StorageCategory.IN_MEMORY,
        Arrays.asList(
            "实时分析性能极高",
            "压缩率高",
            "并行处理能力强"
        ),
        Arrays.asList(
            "成本极高",
            "容量限制",
            "持久化开销大"
        ),
        Arrays.asList("SAP HANA", "Apache Ignite", "Exasol")
    ),
    
    // ==================== 分布式存储类型 ====================
    
    /**
     * 分布式列存储 (Distributed Column Storage)
     * 
     * 特点：
     * - 列式存储 + 分布式
     * - 大规模数据分析
     * - 水平扩展
     * 
     * 典型数据库：ClickHouse Cluster, Apache Druid
     */
    DISTRIBUTED_COLUMN(
        "分布式列存储",
        "Distributed Column Storage",
        StorageCategory.DISTRIBUTED,
        Arrays.asList(
            "PB级数据处理",
            "线性扩展",
            "高可用性",
            "并行查询"
        ),
        Arrays.asList(
            "运维复杂",
            "网络开销",
            "一致性挑战",
            "跨节点查询延迟"
        ),
        Arrays.asList("ClickHouse Cluster", "Apache Druid", "Greenplum")
    ),
    
    /**
     * 对象存储 (Object Storage)
     * 
     * 特点：
     * - 海量非结构化数据
     * - 扁平命名空间
     * - HTTP访问
     * 
     * 典型系统：Amazon S3, MinIO, Ceph
     */
    OBJECT_STORE(
        "对象存储",
        "Object Storage",
        StorageCategory.DISTRIBUTED,
        Arrays.asList(
            "海量存储",
            "高可靠性",
            "成本低",
            "易于扩展"
        ),
        Arrays.asList(
            "延迟较高",
            "不支持随机写",
            "查询能力弱",
            "一致性模型简单"
        ),
        Arrays.asList("Amazon S3", "MinIO", "Ceph", "Azure Blob")
    ),
    
    // ==================== 新兴存储类型 ====================
    
    /**
     * 向量存储 (Vector Storage)
     * 
     * 特点：
     * - 针对向量数据优化
     * - 支持相似度搜索
     * - AI/ML场景
     * 
     * 典型数据库：Milvus, Pinecone, Weaviate
     */
    VECTOR_STORE(
        "向量存储",
        "Vector Storage",
        StorageCategory.EMERGING,
        Arrays.asList(
            "向量搜索高效",
            "支持ANN算法",
            "AI场景优化",
            "高维数据友好"
        ),
        Arrays.asList(
            "通用查询弱",
            "索引构建慢",
            "内存占用大"
        ),
        Arrays.asList("Milvus", "Pinecone", "Weaviate", "Qdrant")
    ),
    
    /**
     * 流存储 (Stream Storage)
     * 
     * 特点：
     * - 针对流数据优化
     * - 实时处理
     * - 事件驱动
     * 
     * 典型系统：Apache Kafka, Apache Pulsar
     */
    STREAM_STORE(
        "流存储",
        "Stream Storage",
        StorageCategory.EMERGING,
        Arrays.asList(
            "实时处理",
            "高吞吐量",
            "持久化消息",
            "回溯能力"
        ),
        Arrays.asList(
            "查询能力有限",
            "存储成本高",
            "复杂查询困难"
        ),
        Arrays.asList("Apache Kafka", "Apache Pulsar", "Amazon Kinesis")
    ),
    
    /**
     * 区块链存储 (Blockchain Storage)
     * 
     * 特点：
     * - 不可篡改
     * - 分布式账本
     * - 共识机制
     * 
     * 典型系统：Ethereum, Hyperledger
     */
    BLOCKCHAIN_STORE(
        "区块链存储",
        "Blockchain Storage",
        StorageCategory.EMERGING,
        Arrays.asList(
            "不可篡改",
            "去中心化",
            "可追溯",
            "高安全性"
        ),
        Arrays.asList(
            "性能低",
            "存储成本高",
            "扩展性差",
            "查询能力弱"
        ),
        Arrays.asList("Ethereum", "Hyperledger", "IPFS")
    );
    
    // ==================== 枚举属性 ====================
    
    private final String chineseName;
    private final String englishName;
    private final StorageCategory category;
    private final List<String> advantages;
    private final List<String> disadvantages;
    private final List<String> typicalDatabases;
    
    StorageType(
        String chineseName,
        String englishName,
        StorageCategory category,
        List<String> advantages,
        List<String> disadvantages,
        List<String> typicalDatabases
    ) {
        this.chineseName = chineseName;
        this.englishName = englishName;
        this.category = category;
        this.advantages = Collections.unmodifiableList(advantages);
        this.disadvantages = Collections.unmodifiableList(disadvantages);
        this.typicalDatabases = Collections.unmodifiableList(typicalDatabases);
    }
    
    // ==================== Getter方法 ====================
    
    public String getChineseName() {
        return chineseName;
    }
    
    public String getEnglishName() {
        return englishName;
    }
    
    public StorageCategory getCategory() {
        return category;
    }
    
    public List<String> getAdvantages() {
        return advantages;
    }
    
    public List<String> getDisadvantages() {
        return disadvantages;
    }
    
    public List<String> getTypicalDatabases() {
        return typicalDatabases;
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 根据分类获取存储类型
     */
    public static List<StorageType> getByCategory(StorageCategory category) {
        List<StorageType> result = new ArrayList<>();
        for (StorageType type : values()) {
            if (type.category == category) {
                result.add(type);
            }
        }
        return result;
    }
    
    /**
     * 获取适合OLTP的存储类型
     */
    public static List<StorageType> getOLTPSuitable() {
        return Arrays.asList(
            ROW_STORE,
            B_PLUS_TREE,
            IN_MEMORY_ROW,
            HYBRID_STORE
        );
    }
    
    /**
     * 获取适合OLAP的存储类型
     */
    public static List<StorageType> getOLAPSuitable() {
        return Arrays.asList(
            COLUMN_STORE,
            DISTRIBUTED_COLUMN,
            IN_MEMORY_COLUMN,
            HYBRID_STORE
        );
    }
    
    /**
     * 获取适合实时分析的存储类型
     */
    public static List<StorageType> getRealTimeSuitable() {
        return Arrays.asList(
            IN_MEMORY_ROW,
            IN_MEMORY_COLUMN,
            STREAM_STORE,
            TIME_SERIES_STORE
        );
    }
    
    /**
     * 打印详细信息
     */
    public String getDetailedInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(chineseName).append(" (").append(englishName).append(") ===");
        sb.append("分类: ").append(category.getChineseName()).append("\n");
        
        sb.append("优点:");
        for (String adv : advantages) {
            sb.append("  ✓ ").append(adv).append("");
        }
        
        sb.append("\n缺点:");
        for (String dis : disadvantages) {
            sb.append("  ✗ ").append(dis).append("");
        }
        
        sb.append("\n典型数据库:");
        for (String db : typicalDatabases) {
            sb.append("  • ").append(db).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 比较两种存储类型
     */
    public static String compare(StorageType type1, StorageType type2) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 存储类型对比 ===\n");
        
        sb.append("类型1: ").append(type1.chineseName).append("");
        sb.append("类型2: ").append(type2.chineseName).append("\n");
        
        sb.append("分类对比:");
        sb.append("  类型1: ").append(type1.category.getChineseName()).append("\n");
        sb.append("  类型2: ").append(type2.category.getChineseName()).append("");
        
        sb.append("优点对比:");
        sb.append("  类型1: ").append(String.join(", ", type1.advantages)).append("");
        sb.append("  类型2: ").append(String.join(", ", type2.advantages)).append("");
        
        sb.append("典型应用:");
        sb.append("  类型1: ").append(String.join(", ", type1.typicalDatabases)).append("\n");
        sb.append("  类型2: ").append(String.join(", ", type2.typicalDatabases)).append("");
        
        return sb.toString();
    }
    
    // ==================== 内部枚举：存储分类 ====================
    
    public enum StorageCategory {
        BASIC("基础存储", "传统的行存储和列存储"),
        FIXED("定长复合列存储", "结合行存储和列存储的优点(多个列组合为一个family)"),
        HYBRID("混合存储", "结合多种存储方式的优点"),
        ADVANCED("高级存储", "基于特定数据结构的存储"),
        SPECIALIZED("专用存储", "针对特定场景优化的存储"),
        IN_MEMORY("内存存储", "全内存或主要基于内存的存储"),
        DISTRIBUTED("分布式存储", "支持分布式架构的存储"),
        EMERGING("新兴存储", "新兴技术和应用场景的存储");
        
        private final String chineseName;
        private final String description;
        
        StorageCategory(String chineseName, String description) {
            this.chineseName = chineseName;
            this.description = description;
        }
        
        public String getChineseName() {
            return chineseName;
        }
        
        public String getDescription() {
            return description;
        }
    }
}

