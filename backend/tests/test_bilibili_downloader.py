from app.downloaders.bilibili_downloader import BilibiliDownloader
from app.services.cookie_manager import CookieConfigManager


def test_cookie_pairs_accept_header_without_spaces():
    assert BilibiliDownloader._cookie_pairs(
        "Cookie: SESSDATA=abc==; bili_jct=xyz; DedeUserID=123"
    ) == [("SESSDATA", "abc=="), ("bili_jct", "xyz"), ("DedeUserID", "123")]


def test_downloader_refreshes_cookie_after_admin_update(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    manager = CookieConfigManager()
    manager.set("bilibili", "SESSDATA=first; bili_jct=token1")
    downloader = BilibiliDownloader()
    first_cookiefile = downloader._cookiefile
    assert first_cookiefile is not None
    with open(first_cookiefile, encoding="utf-8") as cookiefile:
        assert "SESSDATA\tfirst" in cookiefile.read()

    manager.set("bilibili", "SESSDATA=second; bili_jct=token2")
    downloader._refresh_cookie()

    assert downloader._cookie == "SESSDATA=second; bili_jct=token2"
    assert downloader._cookiefile != first_cookiefile
    assert downloader._cookiefile is not None
    with open(downloader._cookiefile, encoding="utf-8") as cookiefile:
        assert "SESSDATA\tsecond" in cookiefile.read()
