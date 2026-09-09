"""文本归一化 / Token 估算 / termScore。"""

from dovideo.core.textnorm import clip, estimate_tokens, normalize, term_score


def test_normalize_strips_cjk_punctuation_and_spaces():
    assert normalize("前序遍历,顺序是根节点、左子树!Right?") == "前序遍历顺序是根节点左子树right"
    assert normalize("  A B\tC  ") == "abc"
    assert normalize("BST: left < root < right") == "bstleftrootright"


def test_estimate_tokens_cjk_heuristic():
    # 非 ASCII 每字符 1 token;ASCII 每 4 字符 1 token
    assert estimate_tokens("中文") == 2
    assert estimate_tokens("abcd") == 1
    assert estimate_tokens("中abcd") == 1 + 1
    assert estimate_tokens("") == 0


def test_term_score_containment():
    assert term_score(["遍历", "平衡树"], "课程讲解三种遍历与 AVL 平衡树") == 1.0
    assert term_score(["遍历", "红黑树"], "课程讲解三种遍历") == 0.5
    assert term_score([], "任意文本") == 0.0
    # 归一化后命中:标点差异不影响
    assert term_score(["前序遍历"], "前序遍历:根 -> 左 -> 右") == 1.0


def test_clip():
    assert clip("abcdefgh", 5) == "abcd…"
    assert clip("abc", 5) == "abc"
