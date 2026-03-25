#!/bin/bash
# UDP性能监控仪表板启动脚本
# 适用于Linux/macOS系统

echo "========================================="
echo "UDP性能监控仪表板启动脚本"
echo "========================================="
echo

# 设置工作目录
WORK_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$WORK_DIR"

# 检查Java环境
if ! command -v java &> /dev/null; then
    echo "[错误] 未找到Java环境，请安装JDK 11或更高版本"
    exit 1
fi

# 检查Maven构建
if [ ! -d "target/classes" ]; then
    echo "[警告] 项目未编译，正在尝试编译..."
    mvn clean compile -q
    if [ $? -ne 0 ]; then
        echo "[错误] 编译失败，请手动运行 mvn clean compile"
        exit 1
    fi
    echo "[成功] 项目编译完成"
fi

# 构建类路径
CLASSPATH="target/classes"
for jar in target/dependency/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done

# 设置Java参数
JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 设置监控参数
MONITOR_OPTS="--start"

# 启动监控服务
echo "[信息] 正在启动UDP性能监控服务..."
echo "[信息] HTTP端口: 8088"
echo "[信息] WebSocket端口: 8089"
echo "[信息] 监控仪表板: http://localhost:8088/monitor"
echo

java $JAVA_OPTS -cp "$CLASSPATH" \
  javax.net.p2p.monitor.web.MonitorDashboardLauncher \
  $MONITOR_OPTS

if [ $? -ne 0 ]; then
    echo "[错误] 监控服务启动失败"
    exit 1
fi

echo
echo "========================================="
echo "监控服务已启动！"
echo "========================================="
echo "访问监控仪表板: http://localhost:8088/monitor"
echo "API接口文档: http://localhost:8088/monitor/api/udp/overview"
echo "WebSocket地址: ws://localhost:8089"
echo "按Ctrl+C停止服务"
echo "========================================="