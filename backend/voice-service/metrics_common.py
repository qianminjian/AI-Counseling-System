"""
Prometheus 手写文本指标公共实现（DA-03：tts/voice 复制共享，D1 config_loader 复制先例）
- 两个微服务各自复制一份本文件（独立发布不共享 wheel），指标名/标签即 alert-rules.yml 的隐式契约
- 仅封装 counter + summary 公共结构；各服务的 gauge 附加行由 /metrics 端点自行构造（引擎/模型就绪语义不同）
"""

import threading


class Metrics:
    """线程安全的手写 Prometheus 文本格式指标（counter + summary）"""

    def __init__(self):
        self._lock = threading.Lock()
        self._counters: dict = {}    # label -> 请求总数
        self._duration_sum = 0.0     # summary 的 _sum（秒）
        self._duration_count = 0     # summary 的 _count

    def record(self, label: str, duration_sec: float):
        """记录一次请求结果（label 维度计数 + summary 累加，线程安全）"""
        with self._lock:
            self._counters[label] = self._counters.get(label, 0) + 1
            self._duration_sum += duration_sec
            self._duration_count += 1

    def render(self, counter_name: str, counter_help: str, label_key: str,
               summary_name: str, summary_help: str, extra_lines=()):
        """渲染 Prometheus 文本格式：counter + summary + 附加 gauge 行"""
        out = [
            f"# HELP {counter_name} {counter_help}",
            f"# TYPE {counter_name} counter",
        ]
        with self._lock:
            for label in sorted(self._counters):
                out.append(f'{counter_name}{{{label_key}="{label}"}} {self._counters[label]}')
            out += [
                f"# HELP {summary_name} {summary_help}",
                f"# TYPE {summary_name} summary",
                f"{summary_name}_sum {self._duration_sum:.6f}",
                f"{summary_name}_count {self._duration_count}",
            ]
        out.extend(extra_lines)
        return "\n".join(out) + "\n"
