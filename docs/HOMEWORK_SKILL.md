# HOMEWORK_SKILL

状态：移植中

本分支先移植网络学堂作业 skill 的“手动提供 Cookie”路径：

1. `get_homework_cookie`：读取用户提供的 Learn Cookie 并在 Android 端缓存。
2. `crawl_course_homeworks`：抓取课程作业记录。
3. `crawl_unsubmitted_homeworks`：抓取未提交作业。
4. `preview_homework_attachments`：打开作业页并解析附件列表。
5. `upload_homework_attachment`：上传一个附件。
6. `submit_homework`：提交作业内容或附件，必须显式传 `confirm_submit=true`。

## 配置

Android 设置页新增三项：

1. 网络学堂地址：默认 `https://learn.tsinghua.edu.cn`
2. 网络学堂 Cookie：用于作业接口请求。
3. 网络学堂 CSRF：可选；如果 Cookie 中包含 `XSRF-TOKEN`，设备端会自动提取。

也可以在 skill 参数中传：

```json
{
  "cookies": "JSESSIONID=...; XSRF-TOKEN=...",
  "csrf_token": "...",
  "learn_base_url": "https://learn.tsinghua.edu.cn"
}
```

## 暂不包含

账号密码自动登录获取 Cookie 暂不在本分支实现。后续应单独做 WebView/浏览器统一登录流程，让用户走清华统一身份认证，再由设备端从浏览器会话中提取网络学堂 Cookie。实现时可参考 `thu-info-community/thu-info-app` 的统一登录流程。
