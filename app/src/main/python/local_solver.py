"""
local_solver.py - 放置于 Android 工程 app/src/main/python/local_solver.py
供 Chaquopy 在端侧离线调用：
1. 接收 Kotlin 端侧 ML Kit 识别的文本，区分题干与几何顶点标签
2. OpenCV 提取几何线段与视觉元数据
3. 自动将题干条件组装为 Inter-GPS/PGDP 形式化谓词
4. 优先调用云端仓库移植的 InterGPS 17 定理符号求解器 (intergps/logic_solver.py) 进行多步推理，未闭合时再用 SymPy 兜底
"""
import json
import os
import re
import sys
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np
import sympy as sp

_INTERGPS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "intergps")
if _INTERGPS_DIR not in sys.path:
    sys.path.insert(0, _INTERGPS_DIR)

try:
    from extended_definition import ExtendedDefinition
    from logic_parser import LogicParser
    from logic_solver import LogicSolver

    _HAS_INTERGPS = True
except Exception:
    _HAS_INTERGPS = False

THEOREM_NAMES_ZH = {
    1: "三角形内角和定理（直接求解）",
    2: "三角形内角和定理（方程构建）",
    3: "等腰三角形性质（等角对等边）",
    4: "等腰三角形性质（等边对等角）",
    5: "全等三角形判定与性质（对应边相等）",
    6: "全等三角形判定与性质（对应角相等）",
    7: "圆的半径处处相等",
    8: "直径所对圆周角为直角 / 切线性质",
    9: "圆心角与圆周角关系定理",
    10: "平行线性质定理（同位角/内错角/同旁内角）",
    11: "平角与邻补角定理",
    12: "相交弦定理",
    13: "多边形内角和定理",
    14: "相似三角形判定与对应边成比例",
    15: "角平分线定理",
    16: "余弦定理",
    17: "正弦定理",
}


def _split_ocr_text(ocr_text: str) -> Tuple[str, List[str]]:
    if not ocr_text or not ocr_text.strip():
        return "", []

    text_lines: List[str] = []
    vertex_points: List[str] = []

    for line in ocr_text.splitlines():
        clean = line.strip()
        if not clean:
            continue
        if re.fullmatch(r"[A-Z]['′]?\d?", clean):
            normalized = clean.replace("′", "'")
            if normalized not in vertex_points:
                vertex_points.append(normalized)
        else:
            text_lines.append(clean)

    question_text = _normalize_math_text("\n".join(text_lines))
    return question_text, vertex_points


def _normalize_math_text(text: str) -> str:
    replacements = {
        "∥": "//",
        "＝": "=",
        "＋": "+",
        "－": "-",
        "×": "*",
        "÷": "/",
        "（": "(",
        "）": ")",
        "，": ",",
        "。": ".",
        "²": "**2",
        "³": "**3",
    }
    for k, v in replacements.items():
        text = text.replace(k, v)
    text = re.sub(r"√\(([^)]+)\)", r"sqrt(\1)", text)
    text = re.sub(r"√([A-Za-z0-9]+)", r"sqrt(\1)", text)
    return text


def _extract_basic_diagram_primitives(img_bgr: np.ndarray) -> Dict[str, Any]:
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 50, 150, apertureSize=3)
    lines_p = cv2.HoughLinesP(
        edges,
        rho=1,
        theta=np.pi / 180,
        threshold=45,
        minLineLength=25,
        maxLineGap=8,
    )

    detected_lines = []
    if lines_p is not None:
        for item in lines_p[:40]:
            x1, y1, x2, y2 = [int(v) for v in item[0]]
            detected_lines.append({"p1": [x1, y1], "p2": [x2, y2]})

    return {
        "image_size": [int(img_bgr.shape[1]), int(img_bgr.shape[0])],
        "detected_segment_count": len(detected_lines),
        "raw_segments": detected_lines,
    }


def _assemble_logic_forms(question_text: str, points: List[str]) -> List[str]:
    forms: List[str] = []

    for m in re.finditer(r"\b([A-Z][']?)([A-Z][']?)\s*=\s*([0-9]+(?:\.[0-9]+)?|sqrt\([^)]+\))", question_text):
        p1, p2, val = m.group(1), m.group(2), m.group(3)
        forms.append(f"Equals(LengthOf(Line({p1}, {p2})), {val})")

    for m in re.finditer(
        r"\b([A-Z][']?)([A-Z][']?)\s*=\s*([A-Z][']?)([A-Z][']?)\b", question_text
    ):
        a1, a2, b1, b2 = m.groups()
        forms.append(f"Equals(LengthOf(Line({a1}, {a2})), LengthOf(Line({b1}, {b2})))")

    for m in re.finditer(r"∠([A-Z]{1,3})\s*=\s*([0-9]+(?:\.[0-9]+)?)°?", question_text):
        ang, val = m.group(1), m.group(2)
        if len(ang) == 3:
            forms.append(f"Equals(MeasureOf(Angle({ang[0]}, {ang[1]}, {ang[2]})), {val})")
        else:
            forms.append(f"Equals(MeasureOf(Angle({ang})), {val})")

    for m in re.finditer(r"\b([A-Z]{2})\s*⊥\s*([A-Z]{2})\b", question_text):
        l1, l2 = m.group(1), m.group(2)
        forms.append(f"Perpendicular(Line({l1[0]}, {l1[1]}), Line({l2[0]}, {l2[1]}))")

    for m in re.finditer(r"\b([A-Z]{2})\s*//\s*([A-Z]{2})\b", question_text):
        l1, l2 = m.group(1), m.group(2)
        forms.append(f"Parallel(Line({l1[0]}, {l1[1]}), Line({l2[0]}, {l2[1]}))")

    for m in re.finditer(r"(Rt)?△([A-Z]{3})", question_text):
        is_rt, tri = m.group(1), m.group(2)
        forms.append(f"Polygon({tri[0]}, {tri[1]}, {tri[2]})")
        if is_rt:
            forms.append(f"Perpendicular(Line({tri[0]}, {tri[1]}), Line({tri[1]}, {tri[2]}))")
        for ch in tri:
            if ch not in points:
                points.append(ch)

    return list(dict.fromkeys(forms))


def _extract_find_target(question_text: str) -> Optional[str]:
    """从中文题干的设问句中提取 InterGPS Find(...) 目标表达式。"""
    m_ang3 = re.search(r"(?:求|计算|则)\s*∠([A-Z]{3})", question_text)
    if m_ang3:
        a = m_ang3.group(1)
        return f"Find(MeasureOf(Angle({a[0]}, {a[1]}, {a[2]})))"

    m_ang1 = re.search(r"(?:求|计算|则)\s*∠([A-Z])\b", question_text)
    if m_ang1:
        return f"Find(MeasureOf(Angle({m_ang1.group(1)})))"

    m_area = re.search(r"△([A-Z]{3}).*面积", question_text)
    if m_area:
        t = m_area.group(1)
        return f"Find(AreaOf(Triangle({t[0]}, {t[1]}, {t[2]})))"

    m_len = re.search(r"(?:求|计算|则)\s*([A-Z]{2})\s*(?:的长|等于|=|\?|$)", question_text)
    if m_len:
        s = m_len.group(1)
        return f"Find(LengthOf(Line({s[0]}, {s[1]})))"

    m_var = re.search(r"(?:求|计算|则)\s*([a-z])\s*(?:的值|=|\?|$)", question_text)
    if m_var:
        return f"Find({m_var.group(1)})"

    return None


def _solve_with_intergps(logic_forms: List[str], question_text: str) -> Optional[Dict[str, Any]]:
    """调用 InterGPS 17 定理符号求解器进行端侧多步推理。"""
    if not _HAS_INTERGPS or not logic_forms:
        return None

    target_form = _extract_find_target(question_text)
    if not target_form:
        return None

    try:
        parser = LogicParser(ExtendedDefinition(debug=False))
        parser.logic.point_positions = None

        for lf in logic_forms:
            tree = parser.parse(lf)
            parser.dfsParseTree(tree[0])

        target_tree = parser.parse(target_form)
        target = parser.findTarget(target_tree[0])
        if target is None:
            return None

        solver = LogicSolver(parser.logic)
        solver.initSearch()
        ans, rounds, step_lst = solver.Search(
            target=target,
            order_list=[],
            round_or_step=True,
            upper_bound=6,
            enable_low_first=True,
        )
        if ans is None:
            return None

        used_theorems = []
        for tid in dict.fromkeys(step_lst):
            name = THEOREM_NAMES_ZH.get(tid)
            if name:
                used_theorems.append(f"应用 **{name}**")

        val_str = f"{ans:.4g}" if isinstance(ans, float) else str(ans)
        steps_md = "\n".join(
            [f"{i + 1}. {desc}" for i, desc in enumerate(used_theorems[:6])]
            + [f"{len(used_theorems[:6]) + 1}. 联立几何约束方程求解得目标值为 **${val_str}$**。"]
        )
        return {
            "solved": True,
            "confidence": 0.92,
            "final_answer": f"${val_str}$",
            "steps_markdown": steps_md,
        }
    except Exception:
        return None


def _solve_with_sympy(logic_forms: List[str], question_text: str) -> Dict[str, Any]:
    """轻量 SymPy 符号求解兜底（用于无明确 Find 目标或纯代数方程）。"""
    equations: List[sp.Eq] = []
    symbol_map: Dict[str, sp.Symbol] = {}
    steps: List[str] = []

    def get_sym(name: str) -> sp.Symbol:
        clean = re.sub(r"[^A-Za-z0-9_]", "_", name)
        if clean not in symbol_map:
            symbol_map[clean] = sp.Symbol(clean, positive=True)
        return symbol_map[clean]

    for form in logic_forms:
        m_tri = re.match(r"Polygon\(([A-Z]),\s*([A-Z]),\s*([A-Z])\)", form)
        if m_tri:
            a, b, c = m_tri.groups()
            sa, sb, sc = get_sym(f"ang_{a}"), get_sym(f"ang_{b}"), get_sym(f"ang_{c}")
            equations.append(sp.Eq(sa + sb + sc, 180))
            steps.append(f"在 $\\triangle {a}{b}{c}$ 中，由内角和定理得 $\\angle {a} + \\angle {b} + \\angle {c} = 180^\\circ$。")
            continue

        m_ang3 = re.match(r"Equals\(MeasureOf\(Angle\(([A-Z]),\s*([A-Z]),\s*([A-Z])\)\),\s*([0-9.]+)\)", form)
        if m_ang3:
            v1, v2, v3, val = m_ang3.groups()
            sym = get_sym(f"ang_{v2}")
            val_sp = sp.nsimplify(val)
            equations.append(sp.Eq(sym, val_sp))
            steps.append(f"由已知得 $\\angle {v1}{v2}{v3} = {val_sp}^\\circ$。")
            continue

        m_ang1 = re.match(r"Equals\(MeasureOf\(Angle\(([A-Z])\)\),\s*([0-9.]+)\)", form)
        if m_ang1:
            v, val = m_ang1.groups()
            sym = get_sym(f"ang_{v}")
            val_sp = sp.nsimplify(val)
            equations.append(sp.Eq(sym, val_sp))
            steps.append(f"由已知得 $\\angle {v} = {val_sp}^\\circ$。")
            continue

        m_iso = re.match(
            r"Equals\(LengthOf\(Line\(([A-Z]),\s*([A-Z])\)\),\s*LengthOf\(Line\(([A-Z]),\s*([A-Z])\)\)\)",
            form,
        )
        if m_iso:
            a1, a2, b1, b2 = m_iso.groups()
            common = {a1, a2} & {b1, b2}
            if len(common) == 1:
                apex = next(iter(common))
                base1 = a2 if a1 == apex else a1
                base2 = b2 if b1 == apex else b1
                equations.append(sp.Eq(get_sym(f"ang_{base1}"), get_sym(f"ang_{base2}")))
                steps.append(f"由 ${a1}{a2} = {b1}{b2}$，等边对等角得 $\\angle {base1} = \\angle {base2}$。")
            continue

        m_len = re.match(r"Equals\(LengthOf\(Line\(([A-Z][']?),\s*([A-Z][']?)\)\),\s*(.+)\)", form)
        if m_len:
            p1, p2, val = m_len.groups()
            sym = get_sym(f"len_{p1}{p2}")
            val_sp = sp.sympify(val)
            equations.append(sp.Eq(sym, val_sp))
            steps.append(f"已知线段 ${p1}{p2} = {sp.latex(val_sp)}$。")

    for match in re.finditer(r"\b([a-z][a-z0-9_+\-*/\s]*)=([a-z0-9_+\-*/\s()]+)", question_text):
        lhs_str, rhs_str = match.group(1).strip(), match.group(2).strip()
        try:
            lhs = sp.sympify(lhs_str)
            rhs = sp.sympify(rhs_str)
            for s in lhs.free_symbols | rhs.free_symbols:
                symbol_map[s.name] = s
            equations.append(sp.Eq(lhs, rhs))
            steps.append(f"列出代数方程：$${sp.latex(lhs)} = {sp.latex(rhs)}$$")
        except Exception:
            pass

    if not equations or not symbol_map:
        return {
            "solved": False,
            "confidence": 0.35,
            "final_answer": "本地定理库暂未闭合求解",
            "steps_markdown": (
                "端侧已完成 OCR 文本与图元提取，但该题需要进一步构造几何推理或辅助线。\n\n"
                "请点击下方 **「用 AI 再解一遍」** 或 **「改用 AI 视觉精解」**。"
            ),
        }

    try:
        sol = sp.solve(equations, list(symbol_map.values()), dict=True)
        if sol:
            first_sol = sol[0]
            formatted_parts = [f"{k} = {sp.latex(v)}" for k, v in first_sol.items()]
            formatted = ", ".join(formatted_parts)
            steps.append(f"联立方程求解得：$${formatted}$$")
            return {
                "solved": True,
                "confidence": 0.88,
                "final_answer": f"${formatted}$",
                "steps_markdown": "\n".join(f"{idx + 1}. {s}" for idx, s in enumerate(steps)),
            }
    except Exception as exc:
        steps.append(f"符号求解异常: {exc}")

    return {
        "solved": False,
        "confidence": 0.4,
        "final_answer": "本地符号方程未能唯一解出",
        "steps_markdown": "建议点击下方 **「用 AI 再解一遍」** 进行深度推理。",
    }


def parse_only(image_path: str, ocr_text: str = "") -> str:
    if not os.path.exists(image_path):
        raise FileNotFoundError(f"图片不存在: {image_path}")

    img_bgr = cv2.imread(image_path)
    if img_bgr is None:
        raise ValueError(f"无法解码图片: {image_path}")

    question_text, points = _split_ocr_text(ocr_text)
    logic_forms = _assemble_logic_forms(question_text, points)
    vision_info = _extract_basic_diagram_primitives(img_bgr)

    result = {
        "question_text": question_text,
        "points": points,
        "diagram_logic_forms": logic_forms,
        "vision_meta": vision_info,
    }
    return json.dumps(result, ensure_ascii=False)


def parse_and_solve_local(image_path: str, ocr_text: str = "") -> str:
    parsed = json.loads(parse_only(image_path, ocr_text))
    logic_forms = parsed.get("diagram_logic_forms", [])
    question_text = parsed.get("question_text", "")

    solve_out = _solve_with_intergps(logic_forms, question_text)
    if solve_out is None:
        solve_out = _solve_with_sympy(logic_forms, question_text)

    payload = {
        "solved": solve_out["solved"],
        "confidence": solve_out["confidence"],
        "final_answer": solve_out["final_answer"],
        "steps_markdown": solve_out["steps_markdown"],
        "parsed_geometry": parsed,
    }
    return json.dumps(payload, ensure_ascii=False)
