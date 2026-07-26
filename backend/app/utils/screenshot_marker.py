import re
from typing import List, Tuple


def extract_screenshot_timestamps(markdown: str) -> List[Tuple[str, int]]:
    """提取所有 Screenshot 标记，返回 [(完整标记文本, 时间戳秒数), ...]。

    支持两种格式（单次扫描，按出现顺序返回）：
    - 被 LLM 包裹为图片链接（优先匹配）：`![alt](*Screenshot-[mm:ss]*)`
    - 独立标记：`*Screenshot-[mm:ss]`
    """
    # 单个正则：包裹格式在前（优先），独立格式在后
    # 两组时间戳捕获共用同一组编号
    pattern = (
        r"!\[([^\]]*)\]\(\*?Screenshot-(?:\[(\d{2}):(\d{2})\]|(\d{2}):(\d{2}))\*\)"
        r"|"
        r"(\*?Screenshot-(?:\[(\d{2}):(\d{2})\]|(\d{2}):(\d{2})))"
    )

    results: List[Tuple[str, int]] = []
    for match in re.finditer(pattern, markdown):
        if match.group(1) is not None:
            # 包裹格式命中：groups 2-5 是时间戳
            mm = match.group(2) or match.group(4)
            ss = match.group(3) or match.group(5)
            results.append((match.group(0), int(mm) * 60 + int(ss)))
        else:
            # 独立格式命中：groups 6-9 是时间戳
            mm = match.group(7) or match.group(9)
            ss = match.group(8) or match.group(10)
            results.append((match.group(6), int(mm) * 60 + int(ss)))

    return results
