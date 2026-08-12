"""
共享配置加载模块（DOC-073 D1，doing/77 §23）
深合并语义：dict 递归合并（override 优先），list/标量整体替换；
边界语义：空 dict 视为无变更（保守保留），None 视为显式置空（整体替换）。
优先级：config.yaml > 代码默认值（defaults 参数）；环境变量覆盖由调用方在加载后应用。
"""
import copy
import logging
import os

import yaml

logger = logging.getLogger(__name__)


def deep_merge(base: dict, override: dict) -> dict:
    """
    递归深合并：dict 逐层合并（override 优先），list/标量整体替换。

    修复浅合并缺陷：嵌套结构（如 voice_personas → persona → 字段）部分配置时
    不再整体替换默认矩阵，仅覆盖指定项。
    """
    result = copy.deepcopy(base)
    for key, value in override.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def load_config(config_path: str = None, defaults: dict = None) -> dict:
    """
    加载 YAML 配置，与代码默认值深合并（yaml 优先；yaml 缺失/损坏时回退默认值）。

    :param config_path: YAML 路径；None 时取模块同目录 config.yaml
    :param defaults: 代码默认值（最小兜底或测试注入的完整矩阵）
    """
    config = deep_merge({}, defaults or {})

    if config_path is None:
        config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml")

    if os.path.isfile(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                file_cfg = yaml.safe_load(f) or {}
            config = deep_merge(config, file_cfg)
            logger.info("✅ 配置已从 %s 加载", config_path)
        except Exception as e:
            logger.warning("配置加载失败，使用代码默认值: %s", e)
    return config
