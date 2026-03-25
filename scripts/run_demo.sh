#!/bin/bash

echo "============================================"
echo "  P2P即时通讯系统 - 演示脚本"
echo "============================================"
echo ""

# 设置环境变量
PROJECT_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$PROJECT_ROOT"

echo "1. 检查环境..."
if ! command -v mvn &> /dev/null; then
    echo "错误: Maven未安装或未配置"
    exit 1
fi
echo "   Maven环境检查通过"
echo ""

echo "2. 编译项目..."
mvn clean compile -q
if [ $? -ne 0 ]; then
    echo "错误: 项目编译失败"
    exit 1
fi
echo "   项目编译成功"
echo ""

show_main_menu() {
    echo "3. 选择演示类型:"
    echo "   1. 点对点聊天演示"
    echo "   2. 群聊功能演示"
    echo "   3. 性能测试演示"
    echo "   4. 完整系统演示"
    echo "   5. API使用示例"
    echo "   6. 退出"
    echo ""
}

chat_demo() {
    echo ""
    echo "============================================"
    echo "   点对点聊天演示"
    echo "============================================"
    echo ""
    echo "启动聊天演示..."
    echo "注意：需要先启动IM服务器（端口6060）"
    echo ""
    
    read -p "是否启动IM服务器？(y/N): " start_server
    if [[ "$start_server" =~ ^[Yy]$ ]]; then
        echo ""
        echo "启动IM服务器..."
        gnome-terminal -- bash -c "java -cp target/classes javax.net.p2p.im.IMServer; exec bash" &
        echo "服务器启动中，等待5秒..."
        sleep 5
        echo ""
    fi
    
    echo "运行聊天演示程序..."
    java -cp target/classes javax.net.p2p.im.ChatDemo
}

group_demo() {
    echo ""
    echo "============================================"
    echo "   群聊功能演示"
    echo "============================================"
    echo ""
    echo "启动群聊演示..."
    echo "注意：需要先启动IM服务器（端口6060）"
    echo ""
    
    read -p "是否启动IM服务器？(y/N): " start_server
    if [[ "$start_server" =~ ^[Yy]$ ]]; then
        echo ""
        echo "启动IM服务器..."
        gnome-terminal -- bash -c "java -cp target/classes javax.net.p2p.im.IMServer; exec bash" &
        echo "服务器启动中，等待5秒..."
        sleep 5
        echo ""
    fi
    
    echo "运行群聊演示程序..."
    java -cp "target/classes:." examples.GroupChatDemo
}

performance_demo() {
    echo ""
    echo "============================================"
    echo "   性能测试演示"
    echo "============================================"
    echo ""
    echo "启动性能测试演示..."
    echo ""
    
    echo "选择性能测试类型:"
    echo "   1. 快速测试 (5客户端，20消息)"
    echo "   2. 标准测试 (10客户端，100消息)"
    echo "   3. 压力测试 (50客户端，500消息)"
    echo "   4. 自定义测试"
    echo "   5. 查看性能优化建议"
    echo "   6. 返回主菜单"
    echo ""
    
    read -p "请选择 (1-6): " perf_choice
    
    case $perf_choice in
        1)
            echo "运行快速性能测试..."
            java -cp "target/classes:." examples.PerformanceDemo 5 20 30
            ;;
        2)
            echo "运行标准性能测试..."
            java -cp "target/classes:." examples.PerformanceDemo 10 100 60
            ;;
        3)
            echo "运行压力测试..."
            java -cp "target/classes:." examples.PerformanceDemo 50 500 120
            ;;
        4)
            read -p "请输入客户端数量: " clients
            read -p "请输入每个客户端发送消息数: " messages
            read -p "请输入测试持续时间(秒): " duration
            echo "运行自定义性能测试..."
            java -cp "target/classes:." examples.PerformanceDemo $clients $messages $duration
            ;;
        5)
            echo "显示性能优化建议..."
            java -cp "target/classes:." examples.PerformanceDemo\$OptimizationGuide
            ;;
        6)
            return
            ;;
        *)
            echo "无效的选择"
            ;;
    esac
}

full_demo() {
    echo ""
    echo "============================================"
    echo "   完整系统演示"
    echo "============================================"
    echo ""
    echo "启动完整系统演示..."
    echo "1. 启动IM服务器"
    echo "2. 启动聊天客户端"
    echo "3. 演示点对点聊天"
    echo "4. 演示群聊功能"
    echo "5. 运行性能测试"
    echo "6. 显示系统状态"
    echo ""
    
    echo "步骤1: 启动IM服务器..."
    gnome-terminal -- bash -c "java -cp target/classes javax.net.p2p.im.IMServer; exec bash" &
    echo "服务器启动中，等待3秒..."
    sleep 3
    echo ""
    
    echo "步骤2: 启动第一个聊天客户端 (用户1)..."
    gnome-terminal -- bash -c "java -cp target/classes javax.net.p2p.im.ChatDemo; exec bash" &
    echo "等待2秒..."
    sleep 2
    echo ""
    
    echo "步骤3: 启动第二个聊天客户端 (用户2)..."
    gnome-terminal -- bash -c "java -cp target/classes javax.net.p2p.im.ChatDemo; exec bash" &
    echo "等待2秒..."
    sleep 2
    echo ""
    
    echo "步骤4: 启动群聊演示..."
    gnome-terminal -- bash -c "java -cp 'target/classes:.' examples.GroupChatDemo; exec bash" &
    echo "等待2秒..."
    sleep 2
    echo ""
    
    echo "步骤5: 运行快速性能测试..."
    gnome-terminal -- bash -c "java -cp 'target/classes:.' examples.PerformanceDemo 3 10 10; exec bash" &
    echo ""
    
    echo "完整系统演示已启动!"
    echo "请查看各个窗口进行交互演示"
    echo ""
    read -p "按回车键继续..."
}

api_demo() {
    echo ""
    echo "============================================"
    echo "   API使用示例"
    echo "============================================"
    echo ""
    echo "选择API示例:"
    echo "   1. 用户管理API示例"
    echo "   2. 消息发送API示例"
    echo "   3. 群聊API示例"
    echo "   4. 客户端API示例"
    echo "   5. 所有API示例"
    echo "   6. 返回主菜单"
    echo ""
    
    read -p "请选择 (1-6): " api_choice
    
    case $api_choice in
        1)
            echo "运行用户管理API示例..."
            echo "TODO: 实现UserManager API示例"
            ;;
        2)
            echo "运行消息发送API示例..."
            echo "TODO: 实现ChatMessageProcessor API示例"
            ;;
        3)
            echo "运行群聊API示例..."
            java -cp "target/classes:." examples.GroupChatDemo\$GroupApiExamples
            ;;
        4)
            echo "运行客户端API示例..."
            echo "TODO: 实现ChatClient API示例"
            ;;
        5)
            echo "运行所有API示例..."
            echo "1. 群聊API示例..."
            java -cp "target/classes:." examples.GroupChatDemo\$GroupApiExamples
            echo ""
            echo "2. 性能测试API示例..."
            java -cp "target/classes:." examples.PerformanceDemo\$BenchmarkExample
            echo ""
            echo "3. 性能优化建议..."
            java -cp "target/classes:." examples.PerformanceDemo\$OptimizationGuide
            ;;
        6)
            return
            ;;
        *)
            echo "无效的选择"
            ;;
    esac
    
    echo ""
    read -p "按回车键继续..."
}

main() {
    while true; do
        show_main_menu
        read -p "请选择演示类型 (1-6): " choice
        
        case $choice in
            1)
                chat_demo
                ;;
            2)
                group_demo
                ;;
            3)
                performance_demo
                ;;
            4)
                full_demo
                ;;
            5)
                api_demo
                ;;
            6)
                echo ""
                echo "============================================"
                echo "   演示完成"
                echo "============================================"
                echo ""
                echo "更多信息:"
                echo "  - 查看演示指南: examples/IM_Demo_Guide.md"
                echo "  - 运行测试: scripts/run_im_tests.sh"
                echo "  - 查看项目文档: README.md"
                echo ""
                exit 0
                ;;
            *)
                echo "无效的选择"
                ;;
        esac
        
        echo ""
        read -p "按回车键返回主菜单..." -r
        echo ""
    done
}

# 运行主函数
main