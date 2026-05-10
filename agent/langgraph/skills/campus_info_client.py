from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from html import unescape
from typing import Any
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener


USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/79.0.3945.88 Safari/537.36"
)

GET_COOKIE_URL = (
    "https://webvpn.tsinghua.edu.cn/"
    "wengine-vpn/cookie?method=get&host=info.tsinghua.edu.cn&scheme=https&path=/f/info/gxfw_fg/common/index"
)
NEWS_LIST_URL = (
    "https://webvpn.tsinghua.edu.cn/https/"
    "77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de/"
    "b/info/xxfb_fg/xnzx/template/more?oType=xs&lydw="
)
SEARCH_NEWS_LIST_URL = (
    "https://webvpn.tsinghua.edu.cn/https/"
    "77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de/"
    "b/xnzx/search/info/xxfb_fg/teacher/getMobilePageList"
)
NEWS_REDIRECT_URL = (
    "https://webvpn.tsinghua.edu.cn/https/"
    "77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de"
)
NEWS_DETAIL_URL = (
    "https://webvpn.tsinghua.edu.cn/https/"
    "77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de/"
    "b/info/xxfb_fg/xnzx/template/detail"
)
FILE_DOWNLOAD_URL = (
    "https://webvpn.tsinghua.edu.cn/https/"
    "77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de/"
    "b/info/wj/download/"
)


CHANNEL_TO_NAME = {
    "LM_BGTG": "办公通知",
    "LM_ZYGG": "重要公告",
    "LM_YQFKZT": "疫情防控专题",
    "LM_JWGG": "教务通知",
    "LM_KYTZ": "科研通知",
    "LM_HB": "海报",
    "LM_XJ_XTWBGTZ": "校团委通知",
    "LM_XSBGGG": "学生工作通知",
    "LM_TTGGG": "图书馆信息",
    "LM_JYGG": "学生社区通知",
    "LM_XJ_XSSQDT": "学生社区动态",
    "LM_BYJYXX": "就业通知",
    "LM_JYZPXX": "招聘信息",
    "LM_XJ_GJZZSXRZ": "国际组织实习任职",
}

ACTIVITY_CHANNELS = ("LM_HB", "LM_XJ_XSSQDT", "LM_JYGG", "LM_KYTZ", "LM_XJ_XTWBGTZ")
DEFAULT_ACTIVITY_KEYWORDS = ("讲座", "活动", "论坛", "沙龙", "报名")


class CampusInfoError(RuntimeError):
    pass


@dataclass
class CampusNewsSlice:
    title: str
    xxid: str
    url: str
    publish_time: str
    source: str
    topped: bool
    channel: str
    in_fav: bool


@dataclass
class CampusNewsDetail:
    title: str
    content_html: str
    abstract: str
    url: str


def _strip_tags(html_text: str) -> str:
    text = re.sub(r"(?is)<(script|style).*?>.*?</\1>", "", html_text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    return re.sub(r"\s+", " ", unescape(text)).strip()


def _decode_html(value: Any) -> str:
    return unescape(str(value or "")).strip()


def _cookie_header_from_session(session: dict[str, Any]) -> str:
    candidates: list[str] = []
    for key in ("cookie", "cookies", "webvpn_cookie", "info_cookie"):
        value = session.get(key)
        if isinstance(value, str) and value.strip():
            candidates.append(value.strip())
        elif isinstance(value, dict):
            joined = "; ".join(f"{k}={v}" for k, v in value.items() if str(k).strip())
            if joined:
                candidates.append(joined)
    env_cookie = os.getenv("OPENTHU_WEBVPN_COOKIE", "").strip()
    if env_cookie:
        candidates.append(env_cookie)
    return candidates[0] if candidates else ""


class CampusInfoClient:
    def __init__(self, session: dict[str, Any], timeout: float = 12.0) -> None:
        self.cookie_header = _cookie_header_from_session(session)
        self.csrf_token = str(session.get("csrf") or session.get("csrf_token") or os.getenv("OPENTHU_WEBVPN_CSRF", "")).strip()
        self.timeout = timeout
        self._opener = build_opener()

    def available(self) -> bool:
        return bool(self.cookie_header or self.csrf_token)

    def _request(self, url: str, post: dict[str, Any] | None = None) -> str:
        headers = {
            "User-Agent": USER_AGENT,
            "Content-Type": "application/x-www-form-urlencoded",
        }
        if self.cookie_header:
            headers["Cookie"] = self.cookie_header
        data = None if post is None else urlencode(post).encode("utf-8")
        request = Request(url, data=data, headers=headers)
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                raw = response.read()
                content_type = response.headers.get("Content-Type", "")
        except URLError as exc:
            raise CampusInfoError(str(exc)) from exc
        charset = "utf-8"
        match = re.search(r"charset=([^;\s]+)", content_type, re.I)
        if match:
            charset = match.group(1)
        try:
            return raw.decode(charset, errors="replace")
        except LookupError:
            return raw.decode("utf-8", errors="replace")

    def csrf(self) -> str:
        if self.csrf_token:
            return self.csrf_token
        html = self._request(GET_COOKIE_URL)
        match = re.search(r"XSRF-TOKEN=(.+?);", html + ";")
        if not match:
            raise CampusInfoError("Unable to locate XSRF-TOKEN from INFO cookie endpoint")
        self.csrf_token = match.group(1)
        return self.csrf_token

    def get_news_list(self, page: int, length: int, channel: str = "all") -> list[CampusNewsSlice]:
        url = f"{NEWS_LIST_URL}&lmid={channel}&currentPage={page}&length={length}&_csrf={self.csrf()}"
        data = json.loads(self._request(url))
        rows = data.get("object", {}).get("dataList", [])
        return [_news_slice_from_row(row) for row in rows if isinstance(row, dict)]

    def search_news_list(
        self,
        page: int,
        keyword: str,
        channel: str | None = None,
        exact_match: bool = False,
    ) -> list[CampusNewsSlice]:
        filter_params = {} if not channel else {"lmmcgroup": CHANNEL_TO_NAME.get(channel, channel)}
        payload = {
            "esParamClass": json.dumps(
                {
                    "params": {"bt": keyword, "tag": keyword, "xxfl": keyword},
                    "filterParams": filter_params,
                    "orderMap": {"sort": "time"},
                    "matchExact": "是" if exact_match else "否",
                    "currentPage": page,
                },
                ensure_ascii=False,
            )
        }
        data = json.loads(self._request(f"{SEARCH_NEWS_LIST_URL}?_csrf={self.csrf()}", payload))
        rows = data.get("object", {}).get("resultsList", [])
        return [_news_slice_from_row(row, search_result=True) for row in rows if isinstance(row, dict)]

    def get_news_detail(self, url: str) -> CampusNewsDetail:
        normalized_url = url if url.startswith("http") else NEWS_REDIRECT_URL + url
        html = self._request(normalized_url)
        xxid = re.search(r'var\s+xxid\s*=\s*"(.*?)";', html)
        if not xxid:
            return CampusNewsDetail(
                title=_extract_title_from_html(html),
                content_html=html,
                abstract=_strip_tags(html),
                url=normalized_url,
            )

        detail_json = self._request(f"{NEWS_DETAIL_URL}?xxid={xxid.group(1)}&preview=&_csrf={self.csrf()}")
        data = json.loads(detail_json)
        dto = data.get("object", {}).get("xxDto", {})
        title = _decode_html(dto.get("bt"))
        content = _decode_html(dto.get("nr"))
        content = content.replace('src="/b/ckeditor/downloadFiles', 'src="/https/77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de/b/ckeditor/downloadFiles')
        for file in dto.get("fjs_template") or []:
            if isinstance(file, dict):
                file_id = str(file.get("wjid", "")).strip()
                file_name = _decode_html(file.get("wjmc"))
                if file_id and file_name:
                    content += f'<a href="{FILE_DOWNLOAD_URL}{file_id}?_csrf={self.csrf()}">{file_name}</a>'
        return CampusNewsDetail(
            title=title,
            content_html=f"<div>{content}</div>",
            abstract=_strip_tags(content),
            url=normalized_url,
        )


def _extract_title_from_html(html: str) -> str:
    for pattern in (r"(?is)<h1[^>]*>(.*?)</h1>", r"(?is)<title[^>]*>(.*?)</title>"):
        match = re.search(pattern, html)
        if match:
            return _strip_tags(match.group(1))
    return ""


def _news_slice_from_row(row: dict[str, Any], search_result: bool = False) -> CampusNewsSlice:
    return CampusNewsSlice(
        title=_strip_tags(_decode_html(row.get("bt"))) if search_result else _decode_html(row.get("bt")),
        xxid=str(row.get("xxid", "")).strip(),
        url=_decode_html(row.get("url")),
        publish_time=str(row.get("time", "")).strip(),
        source=str(row.get("dwmc_show", "")).strip(),
        topped=str(row.get("yxzd", "")).find("1-") >= 0,
        channel=str(row.get("lmid", "")).strip(),
        in_fav=bool(row.get("sfsc", False)),
    )
