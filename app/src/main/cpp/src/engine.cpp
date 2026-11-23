#include "engine.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace mcjl {
namespace {

constexpr const char* kTag = "Aurora-Engine";

void logcat(int priority, std::string_view line) noexcept {
    __android_log_print(priority, kTag, "%.*s", static_cast<int>(line.size()), line.data());
}

// 写 stderr 并显式消费返回值：stderr 不可写时没有更好的上报途径。
void engineLog(const char* fmt, ...) noexcept {
    va_list ap;
    va_start(ap, fmt);
    const int rc = std::vfprintf(stderr, fmt, ap);
    va_end(ap);
    (void)rc;
}

// ---------------------------------------------------------------------------
// 迷你 JSON 解析器：完整支持 string(含转义与 \uXXXX) / number / bool / null /
// array / object。用于解析 Kotlin 侧拼装的完整启动计划（取代早期的字段截取方式）。
// ---------------------------------------------------------------------------
struct Json {
    enum class Type { Null, Bool, Number, String, Array, Object } type = Type::Null;
    bool boolean = false;
    double number = 0.0;
    std::string text;
    std::vector<Json> array;
    std::map<std::string, Json> object;

    [[nodiscard]] std::string asString() const noexcept {
        return type == Type::String ? text : std::string{};
    }
    [[nodiscard]] int asInt(int fallback = 0) const noexcept {
        return type == Type::Number ? static_cast<int>(number) : fallback;
    }
};

class JsonParser {
public:
    explicit JsonParser(std::string_view input) noexcept : in_(input) {}

    std::optional<Json> parse() noexcept {
        skipWs();
        auto value = parseValue();
        if (!value) return std::nullopt;
        skipWs();
        return value;
    }

private:
    std::string_view in_;
    std::size_t pos_ = 0;

    void skipWs() noexcept {
        while (pos_ < in_.size() && std::isspace(static_cast<unsigned char>(in_[pos_]))) ++pos_;
    }

    bool consume(char c) noexcept {
        if (pos_ >= in_.size() || in_[pos_] != c) return false;
        ++pos_;
        return true;
    }

    std::optional<Json> parseValue() noexcept {
        skipWs();
        if (pos_ >= in_.size()) return std::nullopt;
        switch (in_[pos_]) {
            case '{': return parseObject();
            case '[': return parseArray();
            case '"': {
                auto s = parseString();
                if (!s) return std::nullopt;
                Json j; j.type = Json::Type::String; j.text = std::move(*s);
                return j;
            }
            case 't': case 'f': return parseBool();
            case 'n': return parseNull();
            default: return parseNumber();
        }
    }

    std::optional<std::string> parseString() noexcept {
        if (!consume('"')) return std::nullopt;
        std::string out;
        while (pos_ < in_.size()) {
            const char c = in_[pos_++];
            if (c == '"') return out;
            if (c != '\\') { out.push_back(c); continue; }
            if (pos_ >= in_.size()) return std::nullopt;
            const char e = in_[pos_++];
            switch (e) {
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'u': {
                    if (pos_ + 4 > in_.size()) return std::nullopt;
                    unsigned codepoint = 0;
                    for (int i = 0; i < 4; ++i) {
                        const char h = in_[pos_++];
                        codepoint <<= 4u;
                        if (h >= '0' && h <= '9') codepoint += static_cast<unsigned>(h - '0');
                        else if (h >= 'a' && h <= 'f') codepoint += static_cast<unsigned>(h - 'a' + 10);
                        else if (h >= 'A' && h <= 'F') codepoint += static_cast<unsigned>(h - 'A' + 10);
                        else return std::nullopt;
                    }
                    // 基本多文种平面直接转 UTF-8；代理对按 BMP 逐项编码已足够覆盖启动参数
                    if (codepoint < 0x80u) {
                        out.push_back(static_cast<char>(codepoint));
                    } else if (codepoint < 0x800u) {
                        out.push_back(static_cast<char>(0xC0u | (codepoint >> 6u)));
                        out.push_back(static_cast<char>(0x80u | (codepoint & 0x3Fu)));
                    } else {
                        out.push_back(static_cast<char>(0xE0u | (codepoint >> 12u)));
                        out.push_back(static_cast<char>(0x80u | ((codepoint >> 6u) & 0x3Fu)));
                        out.push_back(static_cast<char>(0x80u | (codepoint & 0x3Fu)));
                    }
                    break;
                }
                default: return std::nullopt;
            }
        }
        return std::nullopt;
    }

    std::optional<Json> parseNumber() noexcept {
        const std::size_t begin = pos_;
        if (pos_ < in_.size() && (in_[pos_] == '-' || in_[pos_] == '+')) ++pos_;
        while (pos_ < in_.size() &&
               (std::isdigit(static_cast<unsigned char>(in_[pos_])) || in_[pos_] == '.' ||
                in_[pos_] == 'e' || in_[pos_] == 'E' || in_[pos_] == '-' || in_[pos_] == '+')) ++pos_;
        if (begin == pos_) return std::nullopt;
        const std::string token(in_.substr(begin, pos_ - begin));
        char* end = nullptr;
        const double value = std::strtod(token.c_str(), &end);
        if (end == token.c_str()) return std::nullopt;
        Json j; j.type = Json::Type::Number; j.number = value;
        return j;
    }

    std::optional<Json> parseBool() noexcept {
        Json j; j.type = Json::Type::Bool;
        if (in_.substr(pos_, 4) == "true") { pos_ += 4; j.boolean = true; return j; }
        if (in_.substr(pos_, 5) == "false") { pos_ += 5; j.boolean = false; return j; }
        return std::nullopt;
    }

    std::optional<Json> parseNull() noexcept {
        if (in_.substr(pos_, 4) != "null") return std::nullopt;
        pos_ += 4;
        return Json{};
    }

    std::optional<Json> parseArray() noexcept {
        if (!consume('[')) return std::nullopt;
        Json j; j.type = Json::Type::Array;
        skipWs();
        if (consume(']')) return j;
        while (true) {
            auto item = parseValue();
            if (!item) return std::nullopt;
            j.array.push_back(std::move(*item));
            skipWs();
            if (consume(']')) return j;
            if (!consume(',')) return std::nullopt;
        }
    }

    std::optional<Json> parseObject() noexcept {
        if (!consume('{')) return std::nullopt;
        Json j; j.type = Json::Type::Object;
        skipWs();
        if (consume('}')) return j;
        while (true) {
            skipWs();
            auto key = parseString();
            if (!key || !consume(':')) return std::nullopt;
            auto value = parseValue();
            if (!value) return std::nullopt;
            j.object.insert_or_assign(std::move(*key), std::move(*value));
            skipWs();
            if (consume('}')) return j;
            if (!consume(',')) return std::nullopt;
        }
    }
};

std::vector<std::string> stringArray(const Json& root, std::string_view key) noexcept {
    std::vector<std::string> out;
    if (root.type != Json::Type::Object) return out;
    const auto it = root.object.find(std::string(key));
    if (it == root.object.end() || it->second.type != Json::Type::Array) return out;
    for (const auto& item : it->second.array) {
        if (item.type == Json::Type::String) out.push_back(item.text);
    }
    return out;
}

std::string field(const Json& root, std::string_view key) noexcept {
    if (root.type != Json::Type::Object) return {};
    const auto it = root.object.find(std::string(key));
    return it == root.object.end() ? std::string{} : it->second.asString();
}

/// 从日志内容推断级别，按用户设置的阈值过滤。
int levelOf(std::string_view line) noexcept {
    if (line.find("ERROR") != std::string_view::npos ||
        line.find("Exception") != std::string_view::npos ||
        line.find("FATAL") != std::string_view::npos) return 3;
    if (line.find("WARN") != std::string_view::npos) return 2;
    if (line.find("DEBUG") != std::string_view::npos) return 0;
    return 1;
}

/// OpenJDK java.c 的 JLI_Launch 签名（Android 上以 dlopen 方式进入 JVM，
/// 避免 Android 禁止从可写 app 数据目录 execve 的限制）。
using JliLaunchFn = int (*)(
    int argc, char** argv,
    int jargc, const char** jargv,
    int appclassc, const char** appclassv,
    const char* fullversion, const char* dotversion,
    const char* pname, const char* lname,
    int javaargs, int cpwildcard, int javaw, int ergo);

std::string jsonEscape(std::string_view s) {
    std::string out;
    for (const char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out.push_back(c);
        }
    }
    return out;
}

}  // namespace

Engine& Engine::instance() noexcept {
    static Engine engine;
    return engine;
}

bool Engine::init(std::string_view dataDir, std::string_view nativeLibDir) noexcept {
    {
        std::lock_guard lock(mu_);
        dataDir_ = std::string(dataDir);
        nativeLibDir_ = std::string(nativeLibDir);
        initialized_ = true;
    }
    appendLog(1, "[engine] init dataDir=" + dataDir_);
    appendLog(1, "[engine] init nativeLibDir=" + nativeLibDir_);
    appendLog(1, "[engine] JNI 动态注册已生效（RegisterNatives，无 Java_* 静态导出）");
    return true;
}

std::optional<LaunchConfig> Engine::parseConfig(std::string_view json) const noexcept {
    auto root = JsonParser(json).parse();
    if (!root || root->type != Json::Type::Object) return std::nullopt;

    LaunchConfig cfg;
    cfg.versionId = field(*root, "versionId");
    cfg.javaHome = field(*root, "javaHome");
    cfg.javaBin = field(*root, "javaBin");
    cfg.workDir = field(*root, "workDir");
    cfg.gameDir = field(*root, "gameDir");
    cfg.classpath = field(*root, "classpath");
    cfg.mainClass = field(*root, "mainClass");
    cfg.launchMode = field(*root, "launchMode");
    cfg.jvmArgs = stringArray(*root, "jvmArgs");
    cfg.gameArgs = stringArray(*root, "gameArgs");
    cfg.jliCandidates = stringArray(*root, "jliCandidates");
    cfg.logLevel = 1;
    if (const auto it = root->object.find("logLevel"); it != root->object.end()) {
        cfg.logLevel = it->second.asInt(1);
    }
    if (const auto it = root->object.find("env");
        it != root->object.end() && it->second.type == Json::Type::Object) {
        for (const auto& [k, v] : it->second.object) {
            if (v.type == Json::Type::String) cfg.env.push_back({k, v.text});
        }
    }
    if (cfg.javaBin.empty() || cfg.mainClass.empty() || cfg.classpath.empty()) return std::nullopt;
    if (cfg.launchMode.empty()) cfg.launchMode = "jli";
    if (cfg.workDir.empty()) cfg.workDir = dataDir_;
    return cfg;
}

int Engine::start(std::string_view planJson) noexcept {
    const auto current = state_.load();
    if (current == EngineState::Starting || current == EngineState::Running) {
        appendLog(2, "[engine] 已在运行，忽略重复启动");
        return 2;
    }
    if (!initialized_) {
        appendLog(3, "[engine] 未 init");
        return 3;
    }
    const auto cfg = parseConfig(planJson);
    if (!cfg) {
        state_.store(EngineState::Failed);
        appendLog(3, "[engine] 启动计划 JSON 解析失败或缺少必要字段");
        return 1;
    }
    logLevel_.store(cfg->logLevel);
    if (::access(cfg->workDir.c_str(), F_OK) != 0) {
        state_.store(EngineState::Failed);
        appendLog(3, "[engine] 工作目录不存在: " + cfg->workDir);
        return 6;
    }
    if (::access(cfg->javaBin.c_str(), F_OK) != 0) {
        state_.store(EngineState::Failed);
        appendLog(3, "[engine] 找不到 java: " + cfg->javaBin);
        return 7;
    }

    int outPipe[2]{};
    int inPipe[2]{};
    if (::pipe(outPipe) != 0) {
        appendLog(3, std::string("[engine] stdout pipe 失败: ") + std::strerror(errno));
        state_.store(EngineState::Failed);
        return 4;
    }
    if (::pipe(inPipe) != 0) {
        ::close(outPipe[0]);
        ::close(outPipe[1]);
        appendLog(3, std::string("[engine] stdin pipe 失败: ") + std::strerror(errno));
        state_.store(EngineState::Failed);
        return 4;
    }

    state_.store(EngineState::Starting);
    const pid_t pid = ::fork();
    if (pid < 0) {
        ::close(outPipe[0]); ::close(outPipe[1]);
        ::close(inPipe[0]); ::close(inPipe[1]);
        appendLog(3, std::string("[engine] fork 失败: ") + std::strerror(errno));
        state_.store(EngineState::Failed);
        return 5;
    }

    if (pid == 0) {
        // ---------------- 子进程：真正进入 JVM ----------------
        ::close(outPipe[0]);
        ::close(inPipe[1]);
        ::dup2(outPipe[1], STDOUT_FILENO);
        ::dup2(outPipe[1], STDERR_FILENO);
        ::dup2(inPipe[0], STDIN_FILENO);
        ::close(outPipe[1]);
        ::close(inPipe[0]);

        if (::chdir(cfg->workDir.c_str()) != 0) {
            engineLog("[engine] chdir 失败: %s\n", std::strerror(errno));
            ::_exit(126);
        }
        for (const auto& e : cfg->env) {
            ::setenv(e.key.c_str(), e.value.c_str(), 1);
        }
        setenv("MCJL_LAUNCH_MODE", cfg->launchMode.c_str(), 1);

        std::vector<std::string> args;
        args.emplace_back(cfg->javaBin);
        for (const auto& a : cfg->jvmArgs) args.push_back(a);
        args.emplace_back("-cp");
        args.emplace_back(cfg->classpath);
        args.emplace_back(cfg->mainClass);
        for (const auto& a : cfg->gameArgs) args.push_back(a);

        std::vector<char*> argv;
        argv.reserve(args.size() + 1);
        for (auto& a : args) argv.push_back(a.data());
        argv.push_back(nullptr);

        engineLog("[engine] 启动 %s：%d 个参数 / %zu 个环境变量 / mode=%s\n",
                     cfg->versionId.c_str(), static_cast<int>(args.size()), cfg->env.size(),
                     cfg->launchMode.c_str());

        if (cfg->launchMode == "jli") {
            void* handle = nullptr;
            for (const auto& candidate : cfg->jliCandidates) {
                if (::access(candidate.c_str(), F_OK) != 0) continue;
                handle = ::dlopen(candidate.c_str(), RTLD_NOW | RTLD_GLOBAL);
                if (handle != nullptr) {
                    engineLog("[engine] dlopen 成功: %s\n", candidate.c_str());
                    break;
                }
                engineLog("[engine] dlopen 失败 %s: %s\n",
                             candidate.c_str(), ::dlerror());
            }
            if (handle != nullptr) {
                auto fn = reinterpret_cast<JliLaunchFn>(::dlsym(handle, "JLI_Launch"));
                if (fn != nullptr) {
                    const char* appClass[] = {cfg->mainClass.c_str()};
                    const int rc = fn(
                        static_cast<int>(argv.size() - 1), argv.data(),
                        0, nullptr,
                        1, appClass,
                        "25.0.5", "25",
                        "java", "java",
                        0, 1, 0, 0);
                    engineLog("[engine] JLI_Launch 返回 %d\n", rc);
                    ::_exit(rc);
                }
                engineLog("[engine] dlsym(JLI_Launch) 失败: %s\n", ::dlerror());
            }
            engineLog("[engine] 所有 libjli.so 候选均不可用，回退 execv\n");
        }
        ::execv(cfg->javaBin.c_str(), argv.data());
        engineLog("[engine] execv 失败 %s: %s\n",
                     cfg->javaBin.c_str(), std::strerror(errno));
        ::_exit(127);
    }

    // ---------------- 父进程：日志 / stdin / 采样 / 回收 ----------------
    ::close(outPipe[1]);
    ::close(inPipe[0]);
    childPid_.store(pid);
    stdinFd_.store(inPipe[1]);
    exitCode_.store(-1);
    startedAt_ = std::chrono::steady_clock::now();
    state_.store(EngineState::Running);
    appendLog(1, "[engine] 已拉起 JVM pid=" + std::to_string(pid) +
                 " version=" + cfg->versionId +
                 " classpath=" + std::to_string(std::count(cfg->classpath.begin(), cfg->classpath.end(), ':')) + " 段");

    monitor_ = std::jthread([this, fd = outPipe[0], child = pid](std::stop_token token) {
        monitorChild(fd, child, token);
    });
    return 0;
}

void Engine::monitorChild(int outFd, pid_t child, std::stop_token token) noexcept {
    std::array<char, 4096> buf{};
    bool childReaped = false;
    int tick = 0;
    while (!token.stop_requested()) {
        struct pollfd pfd{outFd, POLLIN, 0};
        const int ready = ::poll(&pfd, 1, 200);
        constexpr std::uint16_t kPollEvents =
            static_cast<std::uint16_t>(POLLIN) | static_cast<std::uint16_t>(POLLHUP);
        if (ready > 0 &&
            (static_cast<std::uint16_t>(pfd.revents) & kPollEvents) != 0u) {
            const ssize_t n = ::read(outFd, buf.data(), buf.size());
            if (n > 0) {
                appendLog(levelOf(std::string_view(buf.data(), static_cast<std::size_t>(n))),
                          std::string_view(buf.data(), static_cast<std::size_t>(n)));
            } else if (n == 0 || errno != EINTR) {
                break;
            }
        }
        // poll 每 200ms 一拍，/proc 采样隔拍做一次即可（400ms），
        // CPU 百分比仍按两次采样的真实时间差计算。
        if ((tick++ % 2) == 0) sampleStats(child);
        int status = 0;
        const pid_t r = ::waitpid(child, &status, WNOHANG);
        if (r == child) {
            const int code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
            exitCode_.store(code);
            childReaped = true;
            break;
        }
    }
    ::close(outFd);
    const int in = stdinFd_.exchange(-1);
    if (in >= 0) ::close(in);

    if (!childReaped) {
        int status = 0;
        ::waitpid(child, &status, 0);
        exitCode_.store(WIFEXITED(status) ? WEXITSTATUS(status) : -1);
    }
    const int code = exitCode_.load();
    state_.store(code == 0 ? EngineState::Idle : EngineState::Failed);
    childPid_.store(static_cast<pid_t>(-1));
    appendLog(code == 0 ? 1 : 3, "[engine] JVM 进程退出 code=" + std::to_string(code));
}

void Engine::sampleStats(pid_t pid) noexcept {
    static std::int64_t lastTicks = 0;
    static auto lastAt = std::chrono::steady_clock::now();
    if (pid <= 0) return;

    std::ifstream stat("/proc/" + std::to_string(pid) + "/stat");
    std::string line;
    std::getline(stat, line);
    std::int64_t utime = 0, stime = 0;
    if (!line.empty()) {
        // 跳过可能含空格的 comm 字段（用最后一个 ')' 定位）
        const auto closeParen = line.rfind(')');
        if (closeParen != std::string::npos) {
            std::istringstream iss(line.substr(closeParen + 2));
            std::int64_t fieldValue = 0;
            int index = 3;   // 从第 3 字段(state) 开始
            while (iss >> fieldValue) {
                if (index == 14) utime = fieldValue;
                if (index == 15) { stime = fieldValue; break; }
                ++index;
            }
        }
    }
    std::ifstream status("/proc/" + std::to_string(pid) + "/status");
    std::int64_t rss = 0;
    std::string key;
    while (status >> key) {
        if (key == "VmRSS:") { status >> rss; break; }
        std::string rest;
        std::getline(status, rest);
    }
    const auto now = std::chrono::steady_clock::now();
    const double seconds = std::chrono::duration<double>(now - lastAt).count();
    const std::int64_t ticks = utime + stime;
    if (seconds > 0.2 && lastTicks > 0) {
        const double hz = static_cast<double>(::sysconf(_SC_CLK_TCK));
        const double cpu = (static_cast<double>(ticks - lastTicks) / hz) / seconds * 100.0;
        cpuPercent_.store(cpu < 0 ? 0 : cpu);
    }
    lastTicks = ticks;
    lastAt = now;
    rssKb_.store(rss);
    elapsedMs_.store(
        std::chrono::duration_cast<std::chrono::milliseconds>(now - startedAt_).count());
}

bool Engine::stop() noexcept {
    const pid_t pid = childPid_.load();
    if (pid <= 0) return false;
    state_.store(EngineState::Stopping);
    appendLog(1, "[engine] 发送 SIGTERM pid=" + std::to_string(pid));
    return ::kill(pid, SIGTERM) == 0;
}

bool Engine::kill() noexcept {
    const pid_t pid = childPid_.load();
    if (pid <= 0) return false;
    appendLog(2, "[engine] 发送 SIGKILL pid=" + std::to_string(pid));
    return ::kill(pid, SIGKILL) == 0;
}

bool Engine::isRunning() const noexcept {
    const auto s = state_.load();
    return s == EngineState::Running || s == EngineState::Starting;
}

int Engine::state() const noexcept { return std::to_underlying(state_.load()); }
int Engine::exitCode() const noexcept { return exitCode_.load(); }

std::string Engine::pollLog() noexcept {
    std::lock_guard lock(mu_);
    std::string out;
    out.swap(logBuffer_);
    return out;
}

std::string Engine::statsJson() const noexcept {
    std::ostringstream os;
    os << "{\"state\":\"" << jsonEscape([this] {
        switch (state_.load()) {
            case EngineState::Idle: return "IDLE";
            case EngineState::Starting: return "STARTING";
            case EngineState::Running: return "RUNNING";
            case EngineState::Stopping: return "STOPPING";
            case EngineState::Failed: return "FAILED";
        }
        return "UNKNOWN";
    }()) << "\""
       << ",\"pid\":" << childPid_.load()
       << ",\"cpuPercent\":" << cpuPercent_.load()
       << ",\"rssKb\":" << rssKb_.load()
       << ",\"elapsedMs\":" << elapsedMs_.load()
       << ",\"exitCode\":" << exitCode_.load()
       << "}";
    return os.str();
}

std::string Engine::version() const noexcept {
    return "Aurora-NativeEngine/1.0.0 | C++" + std::to_string(MCJL_CPP_STANDARD) +
           " | NDK r30 30.0.16248370 | Clang 21 (clang-r574158c)" +
           " | JNI=RegisterNatives | 完整 JSON 计划 / JLI_Launch / stdin / /proc 采样" +
           " | state=" + std::to_string(std::to_underlying(state_.load()));
}

void Engine::setLogLevel(int level) noexcept {
    logLevel_.store(level < 0 ? 0 : (level > 3 ? 3 : level));
}

bool Engine::writeStdin(std::string_view text) noexcept {
    const int fd = stdinFd_.load();
    if (fd < 0) return false;
    std::string payload(text);
    payload.push_back('\n');
    return ::write(fd, payload.data(), payload.size()) == static_cast<ssize_t>(payload.size());
}

void Engine::appendLog(int level, std::string_view line) noexcept {
    if (level < logLevel_.load()) return;
    {
        std::lock_guard lock(mu_);
        logBuffer_.append(line);
        constexpr std::size_t kMaxLogBytes = static_cast<std::size_t>(256) * 1024u;
        if (logBuffer_.size() > kMaxLogBytes) {
            logBuffer_.erase(0, logBuffer_.size() - kMaxLogBytes);
        }
    }
    logcat(level >= 3 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, line);
}

}  // namespace mcjl
