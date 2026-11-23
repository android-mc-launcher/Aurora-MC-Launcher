#pragma once

#include <sys/types.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <optional>
#include <stop_token>
#include <string>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>

static_assert(__cplusplus >= 202302L, "本引擎必须使用 C++23 编译（-std=c++23）");

namespace mcjl {

/// 环境变量条目（由 Kotlin 侧拼装好后传入，不再由 C++ 猜测）。
struct EnvVar {
    std::string key;
    std::string value;
};

/// 完整启动计划：由 Kotlin 的 LaunchConfigBuilder 序列化为 JSON 传入。
/// 所有字段都参与真实启动，由 Kotlin 侧完整提供。
struct LaunchConfig {
    std::string versionId;
    std::string javaHome;
    std::string javaBin;
    std::string workDir;
    std::string gameDir;
    std::string classpath;
    std::string mainClass;
    std::string launchMode;              // "jli" | "exec"
    std::vector<std::string> jvmArgs;
    std::vector<std::string> gameArgs;
    std::vector<std::string> jliCandidates;
    std::vector<EnvVar> env;
    int logLevel = 1;                    // 0 DEBUG / 1 INFO / 2 WARN / 3 ERROR
};

enum class EngineState : std::uint8_t {
    Idle = 0,
    Starting = 1,
    Running = 2,
    Stopping = 3,
    Failed = 4,
};

/// 自研底层引擎：进程拉起、日志管道、stdin、状态机、性能采样。
/// 真实链路：fork → 子进程 dlopen(libjli.so) → JLI_Launch（Pojav 路径）
/// 或 execv(javaBin)，父进程 poll + waitpid + /proc 采样。
class Engine final {
public:
    static Engine& instance() noexcept;

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;
    Engine(Engine&&) = delete;
    Engine& operator=(Engine&&) = delete;

    bool init(std::string_view dataDir, std::string_view nativeLibDir) noexcept;
    int start(std::string_view planJson) noexcept;
    bool stop() noexcept;
    bool kill() noexcept;
    bool isRunning() const noexcept;
    int state() const noexcept;
    int exitCode() const noexcept;
    std::string pollLog() noexcept;
    std::string statsJson() const noexcept;
    std::string version() const noexcept;
    void setLogLevel(int level) noexcept;
    bool writeStdin(std::string_view text) noexcept;

private:
    Engine() = default;
    ~Engine() = default;

    void appendLog(int level, std::string_view line) noexcept;
    void monitorChild(int outFd, pid_t child, std::stop_token token) noexcept;
    void sampleStats(pid_t pid) noexcept;
    std::optional<LaunchConfig> parseConfig(std::string_view json) const noexcept;

    mutable std::mutex mu_{};
    std::string logBuffer_{};
    std::string dataDir_{};
    std::string nativeLibDir_{};
    std::atomic<EngineState> state_{EngineState::Idle};
    std::atomic<pid_t> childPid_{static_cast<pid_t>(-1)};
    std::atomic<int> stdinFd_{-1};
    std::atomic<int> logLevel_{1};
    std::atomic<int> exitCode_{-1};
    std::atomic<double> cpuPercent_{0.0};
    std::atomic<std::int64_t> rssKb_{0};
    std::atomic<std::int64_t> elapsedMs_{0};
    std::chrono::steady_clock::time_point startedAt_{};
    std::jthread monitor_{};
    bool initialized_ = false;
};

}  // namespace mcjl
