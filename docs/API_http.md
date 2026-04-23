# learnX 接口文档

> learnX 是清华大学网络学堂（learn.tsinghua.edu.cn）的第三方移动客户端，基于 React Native 开发。本文档描述其数据获取逻辑与底层 HTTP 接口。

---

## 一、整体架构

```
用户操作
  └─► Redux Action (src/data/actions/)
        └─► Learn2018Helper (thu-learn-lib 库)
              └─► HTTP 请求 → learn.tsinghua.edu.cn / id.tsinghua.edu.cn
                    └─► 返回数据 → Redux Store (src/data/store.ts)
```

### 核心依赖

| 模块 | 说明 |
|------|------|
| `thu-learn-lib` (github:robertying/thu-learn-lib) | 封装了所有对清华网络学堂的 HTTP 请求，是本项目数据获取的核心库 |
| `axios` | 用于文件提交等部分请求 |
| `@react-native-cookies/cookies` | 管理登录 Cookie（JSESSIONID） |
| Redux + typesafe-actions | 状态管理，所有异步数据均通过 Action → Reducer 流转 |

### 数据源入口

文件：`src/data/source.ts`

```ts
export let dataSource: Learn2018Helper;

export const resetDataSource = () => {
  dataSource = new Learn2018Helper({
    provider: () => ({
      username, password, fingerPrint, fingerGenPrint, fingerGenPrint3,
    }),
  });
};
```

`dataSource` 是 `Learn2018Helper` 的全局单例，所有 Action 均通过它发起请求。使用 `provider` 模式时，库在 Session 过期后会自动重新登录。

---

## 二、基础 URL

| 域名 | 用途 |
|------|------|
| `https://id.tsinghua.edu.cn` | 统一身份认证（登录） |
| `https://learn.tsinghua.edu.cn` | 网络学堂主域（所有内容数据） |
| `https://zhjw.cic.tsinghua.edu.cn` | 教务系统（课程日历） |

---

## 三、认证接口

### 3.1 用户名密码登录

| 项目 | 内容 |
|------|------|
| **Action** | `login()` → `src/data/actions/auth.ts` |
| **库方法** | `dataSource.login(username, password, fingerPrint, ...)` |
| **步骤 1** | `POST https://id.tsinghua.edu.cn/do/off/ui/auth/login/form/bb5df85216504820be7bba2b0ae1535b/0` |
| **步骤 2** | `POST https://id.tsinghua.edu.cn/do/off/ui/auth/login/check` <br>携带用户名、密码、指纹信息 |
| **步骤 3** | 获取 `ticket`，跳转至 `https://learn.tsinghua.edu.cn/b/j_spring_security_thauth_roaming_entry?ticket={ticket}` |
| **登录态** | 服务端返回 `JSESSIONID` Cookie，后续请求由 Cookie 鉴权 |
| **自动重登** | 通过 `provider` 回调实现，Session 失效时自动重试 |

**参数说明：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `username` | string | 清华学号 |
| `password` | string | 密码 |
| `fingerPrint` | string | 设备指纹（用于防重放） |
| `fingerGenPrint` | string | 指纹扩展字段 |
| `fingerGenPrint3` | string | 指纹扩展字段 3 |

### 3.2 登出

| 项目 | 内容 |
|------|------|
| **URL** | `GET https://learn.tsinghua.edu.cn/f/j_spring_security_logout` |
| **说明** | 清除服务端 Session；客户端同时清除本地 `JSESSIONID` Cookie |

### 3.3 CSRF Token

所有写操作 URL 均需携带 CSRF Token。通过 `addCSRFTokenToUrl(url, token)` 自动注入到 URL Query 参数。Token 来源：`dataSource.getCSRFToken()`。

---

## 四、学期接口

### 4.1 获取所有学期列表

| 项目 | 内容 |
|------|------|
| **Action** | `getAllSemesters()` → `src/data/actions/semesters.ts` |
| **库方法** | `dataSource.getSemesterIdList()` |
| **URL** | `GET https://learn.tsinghua.edu.cn/b/wlxt/kc/v_wlkc_xs_xktjb_coassb/queryxnxq` |
| **返回值** | `string[]` 学期 ID 列表，降序排列，如 `["2024-2025-2", "2024-2025-1", ...]` |

### 4.2 获取当前学期

| 项目 | 内容 |
|------|------|
| **Action** | `getCurrentSemester()` → `src/data/actions/semesters.ts` |
| **库方法** | `dataSource.getCurrentSemester()` |
| **URL** | `GET https://learn.tsinghua.edu.cn/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester` |
| **返回值** | 当前学期对象（含 `id`，如 `"2024-2025-2"`） |
| **说明** | 若 Redux Store 中尚无当前学期，则自动 dispatch `setCurrentSemester(semester.id)` |

---

## 五、课程接口

### 5.1 获取学期课程列表

| 项目 | 内容 |
|------|------|
| **Action** | `getCoursesForSemester(semesterId)` → `src/data/actions/courses.ts` |
| **库方法** | `dataSource.getCourseList(semesterId, CourseType.STUDENT, lang)` |
| **URL（学生）** | `GET https://learn.tsinghua.edu.cn/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/{semesterId}/{lang}` |
| **URL（教师）** | `GET https://learn.tsinghua.edu.cn/b/kc/v_wlkc_kcb/queryAsorCoCourseList/{semesterId}/0` |

**路径参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `semesterId` | string | 学期 ID，如 `2024-2025-2` |
| `lang` | string | 语言，`zh_CN` 或 `en_US` |

**返回数据（Course 类型）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 课程唯一 ID（wlkcid） |
| `name` | string | 课程名称 |
| `chineseName` | string | 中文名称 |
| `englishName` | string | 英文名称 |
| `teacherNumber` | string | 教师工号 |
| `teacherName` | string | 教师姓名 |
| `courseNumber` | string | 课程编号 |
| `courseIndex` | number | 课序号 |
| `timeAndLocation` | object[] | 上课时间与地点 |
| `semesterId` | string | 所属学期 |

### 5.2 获取课程时间地点

| 项目 | 内容 |
|------|------|
| **URL** | `GET https://learn.tsinghua.edu.cn/b/kc/v_wlkc_xk_sjddb/detail?id={courseID}` |

---

## 六、通知（公告）接口

### 6.1 获取单门课通知列表

| 项目 | 内容 |
|------|------|
| **Action** | `getNoticesForCourse(courseId)` → `src/data/actions/notices.ts` |
| **库方法** | `dataSource.getNotificationList(courseId)` |
| **URL（未过期）** | `GET https://learn.tsinghua.edu.cn/b/wlxt/kcgg/wlkc_ggb/student/pageListXsbyWgq` |
| **URL（已过期）** | `GET https://learn.tsinghua.edu.cn/b/wlxt/kcgg/wlkc_ggb/student/pageListXsbyYgq` |
| **请求参数** | `wlkcid={courseId}` |
| **排序** | 按 `publishTime` 降序 |

### 6.2 批量获取所有课程通知

| 项目 | 内容 |
|------|------|
| **Action** | `getAllNoticesForCourses(courseIds[])` → `src/data/actions/notices.ts` |
| **库方法** | `dataSource.getAllContents(courseIds, ContentType.NOTIFICATION)` |
| **说明** | 并发请求多门课的通知，合并后统一排序 |

**返回数据（Notice 类型）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 通知 ID |
| `title` | string | 通知标题 |
| `publisher` | string | 发布人 |
| `publishTime` | Date | 发布时间 |
| `expireTime` | Date | 过期时间 |
| `markedImportant` | boolean | 是否标记为重要 |
| `content` | string | 通知内容（HTML） |
| `hasRead` | boolean | 是否已读 |
| `attachment` | RemoteFile \| null | 附件信息 |
| `url` | string | 通知详情页面 URL |
| `courseId` | string | 所属课程 ID |
| `courseName` | string | 所属课程名称 |
| `courseTeacherName` | string | 教师姓名 |

**通知附件（RemoteFile）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 文件名 |
| `size` | number | 文件大小（字节） |
| `downloadUrl` | string | 直接下载 URL |
| `previewUrl` | string | 在线预览 URL |

**通知附件下载 URL 格式：**

```
https://learn.tsinghua.edu.cn/b/wlxt/kczy/zy/student/downloadFile/{courseID}/{attachmentID}
```

---

## 七、课程文件接口

### 7.1 获取单门课文件列表

| 项目 | 内容 |
|------|------|
| **Action** | `getFilesForCourse(courseId)` → `src/data/actions/files.ts` |
| **库方法** | `dataSource.getFileList(courseId, CourseType.STUDENT)` |
| **URL（学生）** | `GET https://learn.tsinghua.edu.cn/b/wlxt/kj/wlkc_kjxxb/student/kjxxbByWlkcidAndSizeForStudent?wlkcid={courseID}&size=200` |
| **URL（教师）** | `GET https://learn.tsinghua.edu.cn/b/wlxt/kj/v_kjxxb_wjwjb/teacher/queryByWlkcid?wlkcid={courseID}&size=200` |
| **排序** | 按 `uploadTime` 降序 |

### 7.2 批量获取所有课程文件

| 项目 | 内容 |
|------|------|
| **Action** | `getAllFilesForCourses(courseIds[])` |
| **库方法** | `dataSource.getAllContents(courseIds, ContentType.FILE)` |

### 7.3 按文件分类获取

| 项目 | 内容 |
|------|------|
| **分类列表 URL** | `GET https://learn.tsinghua.edu.cn/b/wlxt/kj/wlkc_kjflb/{courseType}/pageList?wlkcid={courseID}` |
| **按分类获取（学生）** | `GET https://learn.tsinghua.edu.cn/b/wlxt/kj/wlkc_kjxxb/student/kjxxb/{courseID}/{categoryId}` |

**返回数据（File 类型）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 文件 ID |
| `title` | string | 文件标题 |
| `description` | string | 文件描述 |
| `category` | string | 文件分类 |
| `size` | number | 文件大小（字节） |
| `fileType` | string | 文件扩展名（如 `pdf`） |
| `markedImportant` | boolean | 是否标记重要 |
| `isNew` | boolean | 是否为新文件（未读） |
| `uploadTime` | Date | 上传时间 |
| `downloadUrl` | string | 下载 URL |
| `courseId` | string | 所属课程 ID |
| `courseName` | string | 所属课程名称 |
| `courseTeacherName` | string | 教师姓名 |

### 7.4 文件下载

| 项目 | 内容 |
|------|------|
| **下载 URL 格式** | `GET https://learn.tsinghua.edu.cn/b/wlxt/kj/wlkc_kjxxb/{courseType}/downloadFile?sfgk=0&wjid={fileID}` |
| **实现文件** | `src/helpers/fs.ts` → `downloadFile()` |
| **说明** | 下载前需用 `addCSRFTokenToUrl` 注入 CSRF Token；文件保存在本地缓存或文档目录下 `learnX-files/{courseName}/{fileId}/` |

### 7.5 文件在线预览

| 项目 | 内容 |
|------|------|
| **预览 URL 格式** | `GET https://learn.tsinghua.edu.cn/f/wlxt/kc/wj_wjb/{courseType}/beforePlay?wjid={fileID}&mk={mk}&browser=-1&sfgk=0&pageType={first\|all}` |

---

## 八、作业（DDL）接口

### 8.1 获取单门课作业列表

| 项目 | 内容 |
|------|------|
| **Action** | `getAssignmentsForCourse(courseId)` → `src/data/actions/assignments.ts` |
| **库方法** | `dataSource.getHomeworkList(courseId)` |

作业按状态分三个接口并发请求后合并：

| 状态 | URL |
|------|-----|
| 未提交（待完成） | `POST https://learn.tsinghua.edu.cn/b/wlxt/kczy/zy/student/zyListWj` |
| 已提交（未批改） | `POST https://learn.tsinghua.edu.cn/b/wlxt/kczy/zy/student/zyListYjwg` |
| 已批改 | `POST https://learn.tsinghua.edu.cn/b/wlxt/kczy/zy/student/zyListYpg` |

**请求体（Form Data）：**

```
wlkcid={courseId}
```

**排序规则：** 先按截止时间升序展示未过期作业，再按截止时间降序展示已过期作业。

### 8.2 批量获取所有课程作业

| 项目 | 内容 |
|------|------|
| **Action** | `getAllAssignmentsForCourses(courseIds[])` |
| **库方法** | `dataSource.getAllContents(courseIds, ContentType.HOMEWORK)` |

### 8.3 获取作业详情

| 项目 | 内容 |
|------|------|
| **URL** | `POST https://learn.tsinghua.edu.cn/b/wlxt/kczy/zy/student/detail` |
| **请求体** | `id={baseId}`（Form Data） |

### 8.4 提交作业

| 项目 | 内容 |
|------|------|
| **Action** | `submitAssignment()` → `src/data/source.ts` |
| **URL** | `POST https://learn.tsinghua.edu.cn/b/wlxt/kczy/zy/student/tjzy` |
| **Content-Type** | `multipart/form-data` |

**请求体（Form Data）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `xszyid` | string | 学生作业 ID（studentHomeworkId） |
| `zynr` | string | 作业文字内容 |
| `fileupload` | File | 附件文件（可选） |
| `isDeleted` | string | `"1"` 表示删除附件，`"0"` 表示保留 |

**返回数据（Assignment 类型）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 作业 ID |
| `studentHomeworkId` | string | 学生作业 ID（提交时使用） |
| `title` | string | 作业标题 |
| `description` | string | 作业说明（HTML） |
| `deadline` | Date | **截止时间（DDL）** |
| `lateSubmissionDeadline` | Date | 晚交截止时间 |
| `completionType` | string | 完成类型 |
| `submissionType` | string | 提交类型 |
| `attachment` | RemoteFile \| null | 作业附件（老师发的） |
| `submitted` | boolean | 是否已提交 |
| `isLateSubmission` | boolean | 是否晚交 |
| `submitTime` | Date | 提交时间 |
| `submittedContent` | string | 已提交的文字内容 |
| `submittedAttachment` | RemoteFile \| null | 已提交的附件 |
| `graded` | boolean | 是否已批改 |
| `grade` | number | 分数 |
| `gradeLevel` | string | 等级（A+/A/.../F） |
| `graderName` | string | 批改人 |
| `gradeTime` | Date | 批改时间 |
| `gradeContent` | string | 批改意见 |
| `gradeAttachment` | RemoteFile \| null | 批改附件 |
| `answerContent` | string | 参考答案内容 |
| `answerAttachment` | RemoteFile \| null | 参考答案附件 |
| `url` | string | 作业详情页面 URL |
| `excellentHomeworkList` | object[] | 优秀作业列表 |
| `courseId` | string | 所属课程 ID |
| `courseName` | string | 所属课程名称 |
| `courseTeacherName` | string | 教师姓名 |

---

## 九、课程日历接口

### 9.1 获取课程时间表

| 项目 | 内容 |
|------|------|
| **库方法** | `dataSource.getCalendar(startDate, endDate, graduate?)` |
| **步骤 1** | `POST https://learn.tsinghua.edu.cn/b/wlxt/common/auth/gnt`（获取 ticket） |
| **步骤 2** | `GET https://zhjw.cic.tsinghua.edu.cn/j_acegi_login.do?url=/&ticket={ticket}`（教务系统认证） |
| **步骤 3** | `GET https://zhjw.cic.tsinghua.edu.cn/jxmh_out.do?m=bks_jxrl_all&p_start_date={startDate}&p_end_date={endDate}&jsoncallback={callback}` |

**路径参数：**

| 参数 | 格式 | 说明 |
|------|------|------|
| `startDate` | `YYYYMMDD` | 开始日期 |
| `endDate` | `YYYYMMDD` | 结束日期（范围不超过 29 天） |
| `graduate` | boolean | `true` 使用研究生接口（`yjs_jxrl_all`），默认本科生 |

---

## 十、用户信息接口

### 10.1 获取当前用户信息

| 项目 | 内容 |
|------|------|
| **Action** | `getUserInfo()` → `src/data/actions/user.ts` |
| **库方法** | `dataSource.getUserInfo(CourseType.STUDENT)` |
| **说明** | 登录成功后自动调用，获取学生姓名、学号、院系等基本信息 |

**返回数据（User 类型，对应 `UserInfo`）：**

| 字段 | 说明 |
|------|------|
| `name` | 姓名 |
| `id` | 学号 |
| `department` | 院系（自动去除 `(未译)` 后缀） |

---

## 十一、数据流总结

```
App 启动
  ├─ login() ──────────────────────────► id.tsinghua.edu.cn（SSO 登录）
  │                                        └─ 获取 JSESSIONID Cookie
  ├─ getUserInfo() ────────────────────► learn.tsinghua.edu.cn（用户信息）
  ├─ getCurrentSemester() ─────────────► learn.tsinghua.edu.cn（当前学期）
  ├─ getAllSemesters() ─────────────────► learn.tsinghua.edu.cn（学期列表）
  └─ getCoursesForSemester(id) ────────► learn.tsinghua.edu.cn（课程列表）
        └─ 获得 courseIds[]
              ├─ getAllNoticesForCourses(courseIds) ──► 通知列表（含附件）
              ├─ getAllFilesForCourses(courseIds) ────► 文件列表
              └─ getAllAssignmentsForCourses(courseIds) ► 作业/DDL 列表
```

---

## 十二、附加功能

### 课程信息共享（CourseX）

文件：`src/helpers/coursex.ts`

- 将课程数据（时间、地点、教师等）上传至第三方平台 `https://api.tsinghua.app/v1/graphql`
- 使用 GraphQL Mutation `insert_course` 写入
- 仅在用户启用"课程信息共享"设置时触发

### 本地文件缓存

文件：`src/helpers/fs.ts`

- 下载的课程文件保存路径：`{cacheDir 或 documentDir}/learnX-files/{courseName}/{fileId}/{courseName}-{fileTitle}.{ext}`
- 支持 iOS 分享扩展和 Android Intent 导出
- 通过 `react-native-fs` 实现跨平台文件操作

---

*文档生成于 2026-04-22，基于 learnX v16.4.4 代码分析*
