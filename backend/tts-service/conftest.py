"""pytest 共享路径（S-019，doing/93）：py-common 单源挂载到 sys.path（config_loader/metrics_common）"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "py-common"))
