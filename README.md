# Hanji (한지)

**HTML/CSS로 화면을 만들고 Java로 제어하는 데스크톱 UI 프레임워크.**
Swing·JavaFX처럼 `Application → Window → 컴포넌트/이벤트` 모델을 그대로 쓰되, 화면은 HTML로 그립니다.

- **JavaFX·Swing·AWT 의존성 없음**: 코어는 JDK의 `java.base` + `jdk.httpserver` 모듈만 사용합니다(`jdeps`로 확인). 외부 라이브러리도 쓰지 않습니다.
- **Java 17+**: Java 21 이상에서는 백그라운드 작업에 가상 스레드를 사용합니다.
- 창은 설치된 **Chrome/Edge의 앱 모드**(`--app`, 탭과 주소창이 없는 네이티브 창)로 띄웁니다. Windows 10/11에는 Edge가 기본으로 있습니다.
- **Windows · macOS · Linux** 지원. 네이티브 코드가 없어 같은 JAR가 모든 OS에서 그대로 동작합니다.

## 지원 OS

| OS | 창 표시 방식 | 브라우저 자동 탐색 위치 | 검증 방법 |
|---|---|---|---|
| **Linux** (X11 / Wayland) | Chrome·Edge·Chromium·Brave·Vivaldi 앱 모드 → 없으면 `$BROWSER` 또는 `xdg-open`으로 기본 브라우저 탭 | `PATH` → `/usr/bin`, `/usr/local/bin`, `/opt/google/chrome`, `/opt/microsoft/msedge`, Snap(`/snap/bin/chromium`), Flatpak(`/var/lib/flatpak/exports/bin`, `~/.local/share/flatpak/exports/bin`) | Ubuntu 24.04에서 root·일반 사용자로 실제 창 실행 확인 + CI |
| **macOS** (Intel / Apple Silicon) | Chrome·Edge·Chromium·Brave 앱 모드 → 없으면 `open`으로 Safari 탭 | `/Applications`, `~/Applications`의 `.app` 번들 | GitHub Actions macOS 러너 CI(`ci.yml`) |
| **Windows** 10/11 | Edge(기본 탑재)·Chrome 앱 모드 | `Program Files`, `Program Files (x86)`, `%LOCALAPPDATA%` | GitHub Actions Windows 러너 CI(`ci.yml`) |

OS별 동작 차이:
- **macOS**: macOS 앱은 마지막 창을 닫아도 프로세스가 Dock에 남습니다. Hanji는 페이지 연결 끊김으로 창 닫힘을 감지하고(기본 2.5초, `hanji.closeGrace`) 해당 브라우저 프로세스를 직접 종료합니다. Cmd+Q로 종료하면 즉시 감지합니다. Cmd+R 새로고침은 Ctrl+R과 같이 막혀 있습니다.
- **Linux**: root로 실행하면 Chromium 정책상 `--no-sandbox`가 자동으로 붙습니다. 일반 사용자는 브라우저 샌드박스를 그대로 사용합니다. 디스플레이(`DISPLAY` 또는 `WAYLAND_DISPLAY`)가 없으면 `external` 호스트로 전환해 URL만 출력합니다.
- 브라우저가 특이한 위치에 있으면 `-Dhanji.browser=/경로/브라우저` 또는 환경변수 `HANJI_BROWSER`로 지정합니다.

## 프로그래밍 모델 3가지

| 방식 | 대응 개념 | 사용 API |
|---|---|---|
| HTML 템플릿 + 컨트롤러 | JavaFX FXML + `@FXML` | `window.load("page.html", Controller::new)`, `@UiField`, `@UiHandler`, `@JsCallable` |
| Java 코드로 화면 조립 | Swing | `VBox`, `HBox`, `Button`, `TextField`, `TableView` … + `window.setContent(...)` |
| 상태 바인딩 | JavaFX Property | `SimpleProperty`, `ObservableList`, `Bindings`, `bindText/bindEnabled/...` |

세 방식은 섞어서 써도 됩니다. 예를 들어 HTML로 레이아웃을 잡고, `@UiField TableView<Person> table;`처럼 HTML 요소에 컴포넌트를 씌울 수 있습니다.

## 빠른 시작

### 1) HTML + 컨트롤러 (FXML 스타일)

`src/main/resources/web/index.html`
```html
<!DOCTYPE html>
<html lang="ko">
<head><meta charset="utf-8"><title>인사</title></head>
<body>
  <input id="name" placeholder="이름">
  <button id="greet">인사</button>
  <p id="message"></p>
</body>
</html>
```

```java
public class HelloApp extends HanjiApplication {

    public static class HelloController {
        @UiField Element name;      // id="name"
        @UiField Element message;   // id="message"

        void initialize() {                     // 페이지 로드 + 주입 완료 후 호출
            message.setText("이름을 입력하세요");
        }

        @UiHandler("greet")                     // id="greet" 의 click
        void greet() {
            message.setText("안녕하세요, " + name.getValue() + "님");   // 입력값은 동기 조회 가능
        }
    }

    @Override
    public void start(Window window) {
        window.setTitle("Hello").setSize(480, 320)
              .load("index.html", HelloController::new)
              .show();
    }

    public static void main(String[] args) { launch(args); }
}
```

### 2) Java 코드만으로 (Swing 스타일)

```java
Hanji.launch(window -> {
    TextField name = new TextField().setPlaceholder("이름");
    Label message = new Label();
    Button greet = new Button("인사", e -> message.setText("안녕하세요, " + name.getText()));
    greet.bindEnabled(name.textProperty().map(t -> !t.isBlank()));   // 입력이 있을 때만 활성화

    window.setTitle("Hello").setContent(new VBox(name, greet, message)).show();
});
```

### 3) JavaScript ↔ Java

```java
public class Controller {
    @JsCallable                                   // JS: await hanji.call('findUsers', '김')
    List<User> findUsers(String keyword) { ... }  // 인자와 반환값은 JSON으로 자동 변환(record·POJO 지원)

    @JsCallable(background = true)                // UI 스레드가 아닌 워커 스레드에서 실행
    Report heavyReport(ReportRequest req) { ... }
}
```
```js
const users = await hanji.call('findUsers', '김');
hanji.emit('saved', { id: 3 });                    // Java: window.onMessage("saved", Saved.class, s -> ...)
hanji.on('progress', p => bar.style.width = p.pct + '%');   // Java: window.publish("progress", Map.of("pct", 40))
```
Java에서 페이지 쪽 코드를 호출할 수도 있습니다.
```java
window.call("charts.update", data);                         // 전역 JS 함수 호출
window.eval("document.title").thenAccept(t -> ...);         // 결과는 CompletableFuture
element.eval("el.getBoundingClientRect().width");           // el = 해당 요소
```

## 핵심 API

### Window
| 메서드 | 설명 |
|---|---|
| `load(path, controller \| factory)` / `loadHtml(html)` / `setContent(node)` | 페이지 지정 |
| `show()` / `close()` / `onClosed(r)` / `onReady(r)` | 생명주기 |
| `byId(id)` / `body()` / `document()` / `global()` / `query(selector)` | 요소 조회 |
| `alert` / `confirm` / `prompt` | HTML `<dialog>` 기반 비동기 대화상자(`CompletableFuture`) |
| `eval` / `call` / `publish` / `onMessage` / `expose` | JS 연동 |

### Element (DOM 프록시)
`setText`, `setHtml`, `setValue/getValue`, `setChecked/isChecked`, `setAttribute`, `addClass/removeClass/toggleClass`,
`setStyle`, `setVisible`, `setEnabled`, `append/prepend/insertBefore/remove/clear/dispose`, `focus/click/invoke`,
`on(type, [EventOptions], handler)`, `bindText/bindValue/bindChecked/bindVisible/bindEnabled/bindClass/bindAttribute`.

- `Element.create("div")`로 만든 요소는 창에 붙기 전까지 명령을 기록해 두었다가, 붙는 순간 순서대로 재생합니다. 그래서 Swing처럼 트리를 먼저 조립할 수 있습니다.
- 페이지의 `input/select/textarea` 값(`id`가 있는 요소)은 Java로 자동 미러링되므로 `getValue()`는 왕복 통신 없이 즉시 반환됩니다. 그 밖의 DOM 값은 `eval`로 비동기 조회합니다.
- `EventOptions`: `preventDefault()`, `stopPropagation()`, `debounce(ms)`, `delegate(".row")`, `keys("Enter")`.

### 어노테이션
| 어노테이션 | 대상 | 설명 |
|---|---|---|
| `@UiField("id")` | 필드 | `Element`, 컴포넌트(`TextField`, `ListView<T>` …), `Window` 주입. id를 생략하면 필드 이름 사용 |
| `@UiHandler(value = {"id"}, event = "click", debounce, delegate, keys, preventDefault)` | 메서드 `()` 또는 `(Event)` | 이벤트 핸들러. `#document`, `#window`, `#body`도 지정 가능 |
| `@JsCallable(value = "name", background = false)` | 메서드 | `hanji.call(name, ...)`로 호출 가능 |

컨트롤러에 `initialize()` 또는 `initialize(Window)` 메서드가 있으면 주입 후 호출합니다(JavaFX와 동일한 규칙).

### 컴포넌트 (`io.hanji.ui`)
`Label`, `Button`, `TextField`, `PasswordField`, `TextArea`, `CheckBox`, `ComboBox<T>`, `ListView<T>`, `TableView<T>`(헤더 클릭 정렬, 행 선택), `VBox`, `HBox`, `Panel`.
기본 스타일은 `hj-*` 클래스로만 지정되어 있어 페이지 CSS와 충돌하지 않고, `--hj-accent` 같은 CSS 변수로 테마를 바꿀 수 있습니다.

### 스레드 모델
- 모든 UI 코드는 **단일 UI 스레드(`Hanji-UI`)**에서 실행됩니다(Swing EDT, JavaFX Application Thread와 같은 개념). 다른 스레드에서 UI를 건드리면 `IllegalStateException`이 발생합니다.
- 다른 스레드에서 UI를 갱신할 때는 `Hanji.runLater(...)`를 사용합니다.
- `Hanji.async(() -> 느린작업()).thenAccept(r -> ui갱신(r))` 형태로 쓰면 작업은 워커 스레드에서, 후속 처리는 UI 스레드에서 실행됩니다.
- 한 번의 UI 작업에서 발생한 DOM 변경은 작업이 끝날 때 **한 묶음으로** 페이지에 전송됩니다.

## 동작 구조

```
 Java (UI 스레드)                         127.0.0.1 내장 HTTP 서버                   창 (Chrome/Edge --app)
 ────────────────                         ─────────────────────────                  ──────────────────────
 Element.setText(...) ─┐
 Element.on(...)       ├─ 작업 종료 시 배치 ──▶ /{token}/__hanji/events (SSE) ──────▶ hanji.js 가 DOM에 적용
 window.publish(...)  ─┘                                                              │
                                                                                      │ 이벤트, 입력값, hanji.call
 핸들러 실행 ◀──────── UI 스레드로 전달 ◀─── /{token}/__hanji/send (POST, 순서 보장) ◀─┘
```
- 창마다 추측할 수 없는 토큰이 URL에 들어가고, 서버는 루프백(127.0.0.1)에만 바인딩됩니다.
- HTML 응답에는 클라이언트 런타임(`hanji.js`, `hanji.css`)이 `<head>` 맨 앞에 자동 삽입되므로, 페이지 스크립트에서 곧바로 `hanji`를 쓸 수 있습니다.
- SSE는 `Last-Event-ID`로 끊긴 지점부터 재전송하고, 페이지→Java 메시지는 한 번에 하나씩 순서대로 보내며 재시도 시 중복을 제거합니다.
- 창 닫힘은 브라우저 프로세스 종료와 페이지 연결 끊김으로 감지합니다. 마지막 창이 닫히면 앱이 종료됩니다(`setImplicitExit(false)`로 끌 수 있음).

## 창 호스트

| `hanji.host` | 동작 | 자동 선택 순위 |
|---|---|---|
| `app` | Chrome/Edge/Chromium/Brave를 `--app=URL`로 실행해 탭 없는 네이티브 창으로 표시. 창마다 별도 프로세스와 임시 프로필을 사용 | 1 |
| `browser` | 기본 브라우저의 탭으로 열기(OS 명령 `xdg-open`/`open`/`rundll32` 사용) | 2 |
| `external` | 창을 열지 않고 URL만 출력(서버·CI·원격 디버깅용). 디스플레이가 없으면 자동 선택 | 3 |

다른 렌더링 엔진(예: OS 내장 WebView를 JNI/FFM으로 연결)이 필요하면 `io.hanji.spi.WindowHostProvider`를 구현하고 `META-INF/services`에 등록하면 됩니다.

## 설정

`-Dhanji.xxx=` 시스템 속성 또는 `HanjiApplication.configure(HanjiConfig)`로 지정합니다.

| 속성 | 기본값 | 설명 |
|---|---|---|
| `hanji.host` | `auto` | `app` / `browser` / `external` |
| `hanji.webRoot` | `web` | 페이지를 찾는 클래스패스 폴더 |
| `hanji.browser` (환경변수 `HANJI_BROWSER`) | 자동 탐색 | 앱 모드에 사용할 브라우저 실행 파일 |
| `hanji.browserArgs` | | 브라우저 추가 인자(공백 구분). 예: `--remote-debugging-port=9222` |
| `hanji.dev` | `false` | 개발 모드: `src/main/resources`에서 직접 읽어 빌드 없이 F5로 반영 |
| `hanji.devResources` | `src/main/resources` | 개발 모드의 소스 폴더 |
| `hanji.port` | `0`(임의) | 서버 포트. 고정하면 `localStorage`가 실행 간에 유지됨 |
| `hanji.closeGrace` | `2500` | 페이지 연결이 끊긴 뒤 창이 닫힌 것으로 판단하기까지의 시간(ms) |
| `hanji.debug` | `false` | 전송되는 명령 배치 등 상세 로그 출력 |

개발 모드에서 `load(path, Controller::new)`처럼 팩토리를 넘기면, F5로 새로고침할 때마다 컨트롤러가 새로 만들어집니다(실행 중 화면 수정 반영). 개발 모드가 아닐 때는 F5/Ctrl+R이 막혀 있습니다.

## 프로젝트 구성과 실행

```
hanji/
├─ hanji-core/       프레임워크 (외부 의존성 없음)
│  ├─ src/main/java/io/hanji/           Hanji, HanjiApplication, Window, Element, Event, 어노테이션, 브리지
│  ├─ src/main/java/io/hanji/ui/        컴포넌트
│  ├─ src/main/java/io/hanji/property/  Property, ObservableList, Bindings
│  ├─ src/main/java/io/hanji/json/      JSON 파서/매퍼
│  ├─ src/main/java/io/hanji/spi|host/  창 호스트 SPI와 구현
│  └─ src/main/resources/io/hanji/client/  hanji.js, hanji.css
└─ hanji-examples/   todo(HTML+컨트롤러), components(순수 Java), monitor(JS 차트 + Java 데이터)
```

```bash
mvn install                                                    # 빌드 + 테스트
mvn -pl hanji-examples exec:java -Dexample=todo                # 또는 components, monitor
mvn -pl hanji-examples exec:java -Dexample=todo -Dhanji.dev=true -Dhanji.devResources=hanji-examples/src/main/resources
```

다른 프로젝트에서 사용할 때:
```xml
<dependency>
    <groupId>io.hanji</groupId>
    <artifactId>hanji-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```
JPMS(module-info)를 쓰는 경우 `requires io.hanji;`를 추가하고, 컨트롤러가 있는 패키지는 리플렉션을 위해 `opens com.example.ui to io.hanji;`로 열어야 합니다.

## 테스트

- 단위 테스트: JSON, 매퍼, Property/ObservableList, Element 명령 버퍼링, HTML 주입
- E2E 테스트(`BrowserE2ETest`): 헤드리스 Chromium을 JDK WebSocket 기반 DevTools 프로토콜로 조작해 컨트롤러 주입, 이벤트, 입력값 미러링, 폼→record 변환, JS↔Java 호출, 다이얼로그, 새로고침 후 재초기화, 순수 Java 화면, 창 닫힘 감지를 검증합니다. Chromium 계열 브라우저가 없으면 건너뜁니다.
- 앱 창 테스트(`AppHostE2ETest`): 실제 `app` 호스트로 현재 OS의 브라우저를 찾아 앱 창을 띄우고 조작한 뒤, 사용자가 창을 닫은 상황을 재현해 애플리케이션이 종료되는지 확인합니다. 디스플레이가 필요합니다(Linux CI는 `xvfb-run`).

```bash
mvn install                                         # 브라우저 자동 탐색
HANJI_BROWSER=/path/to/chrome mvn -pl hanji-core test
xvfb-run -a mvn install                             # 화면 없는 Linux에서 앱 창 테스트까지 실행
```

브라우저나 디스플레이가 없으면 E2E 테스트는 건너뜁니다. 환경변수 `HANJI_REQUIRE_E2E=1`을 주면 건너뛰지 않고 실패로 처리합니다(CI에서 사용).

### CI
`.github/workflows/ci.yml`이 **Ubuntu · macOS · Windows × Java 17 · 21** 조합으로 빌드와 전체 테스트를 실행합니다. GitHub 호스팅 러너에는 Google Chrome이 기본 설치돼 있어 별도 설정이 필요 없습니다. CI는 `HANJI_REQUIRE_E2E=1`로 실행하므로 E2E가 건너뛰어진 채 성공으로 끝나는 일이 없고, 테스트 클래스별 실행·건너뜀 개수는 실행 화면의 주석(annotation)으로 남습니다. Mac이 없어도 macOS 동작은 이 CI 결과로 확인할 수 있습니다.

## 현재 제약 사항

- 창 크기·위치는 `show()` 시점에만 적용됩니다. 실행 중 크기 변경, 최소화, 네이티브 메뉴, 시스템 트레이는 아직 지원하지 않습니다.
- `app` 호스트를 쓰려면 Chromium 계열 브라우저가 필요합니다(Windows는 Edge 기본 탑재, macOS·Linux는 Chrome/Edge/Chromium 설치 필요). 없으면 기본 브라우저 탭(`browser`, macOS는 Safari)으로 대체되며, 이때는 주소창이 보이는 일반 탭입니다.
- 폼 입력값 외의 DOM 값은 동기적으로 읽을 수 없습니다(`eval`은 비동기). UI 스레드에서 `future.join()`으로 기다리면 교착 상태가 되므로 `thenAccept`를 사용하세요.
- `clear()`로 지운 자식 요소는 페이지에서 폐기됩니다. 다시 쓰려면 `remove()`로 떼어 두거나 새로 만드세요.
- `browser` 호스트에서 탭을 6개 넘게 열면 브라우저의 호스트당 연결 수 제한에 걸릴 수 있습니다(`app` 호스트는 창마다 별도 프로세스라 해당 없음).
