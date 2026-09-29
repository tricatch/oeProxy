# oe-proxy

`oe-proxy`는 SSL 패스스루(virtual-host) 리버스 프록시 핵심 엔진을, 이 라이브러리를 사용하는
애플리케이션의 DB/웹 프레임워크/i18n 등과 완전히 분리한 독립 라이브러리입니다. 자체 서명 루트
인증서 기반으로 여러 가상 호스트의 HTTPS를 한 포트에서 종료하고, 도메인/경로별로 설정된 백엔드로
요청을 중계합니다.

## 의존성 추가

```kotlin
dependencies {
    implementation("io.github.tricatch:oe-proxy:0.1.0")
}
```

## 최소 사용 예

```java
// 1. CA 인증서/개인키 경로 설정 (없으면 startSslPassServer()가 NotReadyCaException을 던짐)
ReverseProxyServer.setCaFiles(certPath, priKeyPath, priKeyPassword);

// 2. 가상 호스트를 지연 로딩할 SPI 등록 (오너의 userNo, 요청 클라이언트 IP를 받아
//    병합된 vhost YAML 문자열을 반환)
ReverseProxyServer.setVirtualHostsLoader((userNo, clientIp) -> loadYamlFor(userNo, clientIp));

// 3. (선택) 임베딩 애플리케이션이 자체 브랜드의 에러 페이지를 렌더링하고 싶다면 등록
ReverseProxyServer.setErrorPageRenderer(new MyErrorPageRenderer());

// 4. 서버 시작 (SSL 컨텍스트 초기화 포함)
ReverseProxyServer.startSslPassServer();
```

가상 호스트 설정을 직접 적용하려면:

```java
ReverseProxyServer.setVirtualHosts(userNo, virtualHostsConfigYaml);
```

세 가지 런타임 토글(`ipIdentifierEnabled`, `trustInternalCertEnabled`, `internalOnlyUpstream`)은
메모리에만 존재하는 단순 getter/setter이며, 영속화는 임베딩 애플리케이션의 몫입니다.

## 오너 식별 헤더

여러 오너를 다루는 임베딩 애플리케이션은 요청마다 오너를 식별하는 HTTP 헤더를 붙여 보냅니다.
기본 헤더 이름은 `ReverseProxyServer.DEFAULT_IDENTIFIER_HEADER`(`X-Oe-Identifier`)이며,
임베딩 애플리케이션이 이미 자체적으로 쓰고 있는 헤더 이름이 따로 있다면
`ReverseProxyServer.setIdentifierHeaderName(String)`으로 바꿀 수 있습니다:

```java
ReverseProxyServer.setIdentifierHeaderName("X-My-App-Owner");
```

헤더 이름은 `null`이거나 공백일 수 없으며, 대소문자 구분 없이 매칭됩니다. 잘못된 값이 온 경우의
오류 메시지에도 현재 설정된 헤더 이름이 그대로 노출됩니다(`getIdentifierHeaderName()`).

독립 실행(standalone) 모드는 단일 소유자 모드이므로 이 헤더를 전혀 쓰지 않습니다 - 모든 요청이
`ReverseProxyServer.setDefaultOwner`로 지정된 단 하나의 오너로 해석됩니다.

## 오너 식별자 인코딩 (`IdentifierCodec`)

식별 헤더에 담긴 값을 오너 id로 바꾸는 방법은 `tricatch.oe.proxy.spi.IdentifierCodec` 인터페이스로
분리되어 있으며, 라이브러리는 특정 인코딩 방식을 강제하지 않습니다:

```java
public interface IdentifierCodec {
    String encode(long ownerId);
    Long decode(String value);
}
```

기본값은 `PlainIdentifierCodec`으로, 헤더 값을 오너 id의 10진수 문자열 그대로 신뢰합니다(예:
`"42"`). 이 기본값은 인증이 전혀 없으므로, 클라이언트가 아무 오너 id나 자유롭게 주장할 수 있다는
뜻입니다 - 단일 소유자(독립 실행) 배포처럼 이 헤더를 신뢰할 수 있는 환경에서만 적합합니다.

여러 오너를 다루면서 이 헤더가 신뢰할 수 없는 클라이언트에도 노출되는 배포라면, 위조가 불가능한
자체 코덱(예: HMAC 서명 기반)을 만들어 등록해야 합니다:

```java
ReverseProxyServer.setIdentifierCodec(new MyHmacSignedIdentifierCodec(secret));
```

코덱은 `null`일 수 없으며, `decode()`는 값이 잘못되었을 때 예외 대신 `null`을 반환해야 합니다
(예외를 던지면 요청 처리 자체가 실패합니다). 현재 등록된 코덱은 `getIdentifierCodec()`으로 조회할
수 있습니다.

## 트래픽 이벤트 구독 (`HttpEventManager`)

임베딩 애플리케이션은 `HttpEventConsumer`를 구현해 프록시를 지나가는 트래픽 이벤트(라이브
모니터 등)를 구독할 수 있습니다. `clientId`는 인코딩된 오너 식별자(`IdentifierCodec.encode`
결과), `channelId`는 구독자(예: 브라우저 탭)별 키이며, 같은 오너에 여러 채널을 등록할 수 있습니다.

```java
HttpEventConsumer consumer = new HttpEventConsumer() {
    public String getClientId()  { return ReverseProxyServer.getIdentifierCodec().encode(ownerId); }
    public String getChannelId() { return "my-monitor"; }
    public void process(HttpEvent event) throws IOException {
        System.out.println(event.getType() + " " + event.getRid());
    }
};

HttpEventManager.getInstance().addEventConsumer(consumer);
// ... 구독 종료 시
HttpEventManager.getInstance().removeEventConsumer(consumer);
```

- 이벤트는 해당 오너를 구독하는 소비자가 **있는 동안에만** 만들어집니다. 구독자가 없으면 프록시는
  이벤트를 만들지도 큐에 넣지도 않으므로 추가 비용이 없습니다.
- 워커 스레드(4~8개)는 첫 `addEventConsumer()` 호출 때 시작됩니다. 아무도 구독하지 않는
  프록시(독립 실행 기본값 포함)에서는 스레드가 전혀 만들어지지 않습니다.
- `process()`가 예외를 던지면 그 채널만 구독이 해제되고, 같은 오너의 다른 채널은 영향받지 않습니다.
- 이벤트 종류(`HttpEventType`): `REQ_HEADER`, `REQ_BODY`, `RES_HEADER`, `RES_BODY`, `WS_FRAME`.
  `event.getRid()`가 같은 요청/응답 이벤트를 묶는 요청 id입니다.
- 헤더 이벤트(`REQ_HEADER`, `RES_HEADER`)는 `event.getHttpStream()`으로 본문 전송 방식
  (`NONE`, `CONTENT_LENGTH`, `CHUNKED`, `UNTIL_CLOSE`, `WEBSOCKET` 등)를 함께 전달합니다.
  `NONE`/`NULL`이면 이어지는 `REQ_BODY`/`RES_BODY` 이벤트가 오지 않으므로 기다릴 필요가 없습니다.
- `event.getHeaders()`는 이벤트를 만드는 시점의 **스냅샷 복사본**(`HeaderLines.copy()`)입니다.
  프록시가 이후 헤더를 고쳐 써도 영향받지 않습니다. `REQ_HEADER`는 요청 헤더 규칙
  (add/remove header)이 적용되기 전, 클라이언트에게서 받은 그대로의 헤더입니다.
  워커 스레드가 여러 개라 같은 `rid`의 이벤트가 순서대로 처리된다는 보장이 없습니다.
- `HttpEventMonitorConsumer`는 이벤트를 SSE 형식으로 내보내는 기성 구현입니다.

## 빌드

```bash
./gradlew build
./gradlew publishToMavenLocal
```

이 라이브러리를 사용하는 애플리케이션의 저장소와 형제 디렉터리(`../oeProxy`)에 이 프로젝트가
존재하면, 해당 애플리케이션의 `settings.gradle.kts`가 `includeBuild`로 이 프로젝트를 직접
빌드에 포함시켜 Maven Central에 게시되기 전에도 최신 소스로 개발할 수 있습니다.

### 빌드 결과물

`./gradlew build`는 `build/libs/`에 서로 용도가 다른 두 개의 jar를 만듭니다:

- `oe-proxy-0.1.0.jar` - 의존성 미포함, 라이브러리용. 이 프로젝트 자신의 클래스만 담고 있으며
  의존성은 POM(또는 Gradle module metadata)을 통해 해석됩니다. Maven Central에 게시되는
  대상은 이 jar 하나뿐입니다.
- `oe-proxy-0.1.0-all.jar` - 모든 런타임 의존성(slf4j-simple 포함)을 담은 자체 실행형(fat)
  jar. 다른 어떤 것도 클래스패스에 없어도 `java -jar oe-proxy-0.1.0-all.jar`만으로 독립
  실행(standalone) CLI를 바로 쓸 수 있습니다 - 아래 "독립 실행" 절 참고.

## 독립 실행 (standalone)

임베딩 애플리케이션 없이도, `oe-proxy` CLI 하나로 (1) 자체 서명 루트 CA를 만들고 (2) 라우팅만
적어 넣은 YAML 파일 하나로 리버스 프록시를 띄울 수 있습니다. 단일 소유자 모드이므로, 어떤
오너 식별 헤더 없이도 이 YAML의 라우팅 규칙이 모든 요청에 그대로 적용됩니다
(`ReverseProxyServer.setDefaultOwner` - 여러 오너를 다루는 임베딩 애플리케이션은 이 메서드를
호출하지 않으므로 동작에 영향이 없습니다).

```
oe-proxy ca  [outDir] [--name "<CA 공통 이름>"] [--force]
oe-proxy run <routes.yml> [--ca-dir <dir>] [--allow-external-upstream] [--monitor[=basic|headers|full]]
```

`outDir`/`--ca-dir`의 기본값은 둘 다 `<사용자 홈>/oeProxy/root-ca`입니다 (이 라이브러리를
사용하는 애플리케이션의 자체 root-ca 구조와 동일한 형태).

### 1. CA 생성

```bash
./gradlew installDist
build/install/oe-proxy/bin/oe-proxy ca
```

`<사용자 홈>/oeProxy/root-ca/ca.cer`(인증서)와 `ca.pfx`(개인키, 암호 없음)가 생성됩니다.
`ca.pfx`는 POSIX 파일시스템에서는 소유자 전용 권한(`rw-------`)으로 제한되며, Windows에서는
이 단계가 조용히 건너뛰어집니다(NTFS ACL이 이미 현재 사용자로 제한되어 있기 때문). 이미 두
파일이 존재하면 `--force` 없이는 실패합니다.

`ca.cer`를 OS/브라우저 신뢰 저장소에 등록해야 브라우저가 경고 없이 접속할 수 있습니다:

- **Windows** (관리자 권한 필요): `certutil -addstore -f Root ca.cer`
- **macOS**: `sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain ca.cer`
- **Firefox**: OS 신뢰 저장소를 쓰지 않고 자체 인증서 저장소를 사용하므로 별도로 가져와야 합니다
  (설정 → 개인정보 및 보안 → 인증서 보기 → 가져오기).

`ca.pfx`(개인키)는 이 CA로 서명된 모든 인증서를 위조할 수 있는 비밀입니다. 안전하게 보관하고,
공개 저장소에 커밋하지 마십시오.

### 2. 라우팅 YAML 작성

`conf/routes.sample.yml`을 참고하십시오. 임베딩 애플리케이션 사용자가 작성하는 것과 동일한
`virtual:` 스키마 하나만 담습니다 (CA 파일 경로는 YAML이 아니라 CLI 옵션으로 주며, 리스닝 포트는
항상 443입니다):

```yaml
virtual:
  - domain: app.test
    location:
      - host: http://127.0.0.1:8080
        path: [ /** ]
```

- `location[].host`의 상대경로는 지원하지 않으며, `http://`/`https://` 백엔드 URL을 그대로 씁니다.
- 기본적으로 백엔드는 내부망 주소(loopback/private/link-local)만 허용됩니다. 외부 백엔드를 쓰려면
  `run` 명령에 `--allow-external-upstream`을 추가하십시오.
- 알 수 없는 최상위 키는 오류가 아니라 경고만 남기고 무시됩니다.

항상 443 포트로 실행하며, 리눅스/macOS에서는 1024 미만 포트를 열려면 관리자(root) 권한이
필요합니다(Windows는 보통 불필요합니다).

### 3. 실행

```bash
build/install/oe-proxy/bin/oe-proxy run ./routes.yml
```

또는 Gradle로 바로:

```bash
./gradlew run --args="ca ./conf"
./gradlew run --args="run ./conf/routes.sample.yml"
```

- 항상 443 포트로 실행합니다. 리눅스/macOS는 1024 미만 포트를 열려면 관리자(root) 권한이
  필요합니다(`sudo` 또는 `setcap`); Windows는 보통 불필요합니다.
- `--ca-dir` 생략 시 `<사용자 홈>/oeProxy/root-ca`(위 `ca` 명령의 기본 출력 위치와 동일)를
  사용합니다.
- 요청을 라우팅하려는 각 도메인을 `/etc/hosts`(또는 Windows의
  `C:\Windows\System32\drivers\etc\hosts`)에서 이 프로세스가 도는 서버의 IP로 매핑해 두어야
  브라우저/클라이언트가 해당 이름으로 접속할 수 있습니다.
- 종료는 `Ctrl+C`(또는 프로세스 종료 신호) - 종료 훅이 "stopping" 로그를 남깁니다.
- routes.yml이 없거나, CA 파일이 없거나(`ca-dir`에 `ca.cer`/`ca.pfx`가 없으면 `oe-proxy ca`를
  먼저 실행하라는 안내가 나옵니다), 포트가 이미 사용 중이면 표준에러에 한 줄 오류 메시지를 남기고
  종료 코드 1로 끝납니다. `-Doe.proxy.debug=true`를 주면 스택 트레이스도 함께 출력합니다.
- `--monitor`를 주면 요청마다 결과를 표준출력(stdout)에 출력합니다. 값 없이 `--monitor`만 쓰면
  `basic`과 같습니다. 이 옵션을 켠 경우에만 이벤트 워커 스레드가 시작됩니다. 요청 하나의 출력은
  한 덩어리로 나가므로 동시 요청의 출력이 서로 섞이지 않습니다.

  | 수준 | 출력 |
  | --- | --- |
  | `basic` | 요청당 한 줄(요약): `시각  METHOD host/path -> 상태  소요시간` |
  | `headers` | 요약 한 줄 + 요청 헤더(`  > `)와 응답 헤더(`  < `) |
  | `full` | `headers` + 요청/응답 본문, 그리고 WebSocket 프레임 |

  `basic` 예:

  ```
  14:03:22.417  GET app.test/api/items?x=1 -> 200  42ms
  ```

  `headers` 예:

  ```
  14:03:22.417  GET app.test/api/items?x=1 -> 200  42ms
    > GET /api/items?x=1 HTTP/1.1
    > Host: app.test
    > Accept: */*
    < HTTP/1.1 200 OK
    < Content-Type: application/json
    < Content-Length: 11
  ```

  `full` 예 (본문이 있으면 각 헤더 블록 바로 뒤에 붙습니다):

  ```
  14:03:22.417  POST app.test/submit -> 200  42ms
    > POST /submit HTTP/1.1
    > Host: app.test
    > Content-Type: application/json
    > Content-Length: 7
    > [body 7 bytes]
    > {"a":1}
    < HTTP/1.1 200 OK
    < Content-Type: application/json
    < Content-Encoding: gzip
    < [body 31 bytes, gzip -> 11 bytes]
    < {"ok":true}
  14:03:23.001  [ws w1] > text: hello
  14:03:23.020  [ws w1] < text: world
  ```

  `full` 수준의 동작 방식:
  - 요청/응답 본문이 모두 끝난 뒤에 한 번에 출력합니다. `NONE`/`NULL`/`WEBSOCKET` 본문, HEAD 요청의
    응답, 1xx/204/304 응답은 본문이 없는 것으로 보고 기다리지 않습니다. 본문이 비어 있으면 본문
    블록을 출력하지 않습니다.
  - `Content-Encoding`이 gzip/deflate이면 풀어서 보여 주고(`[body 원본 bytes, gzip -> 풀린 bytes]`),
    br, zstd 등 그 밖의 인코딩은 `[body N bytes, br-encoded]`처럼 크기만 표시합니다.
  - 텍스트(`text/*`, JSON, XML, JavaScript, `x-www-form-urlencoded`, 또는 `Content-Type`이 없고
    유효한 UTF-8인 경우)만 내용을 출력하며 앞 2048바이트까지만 보이고 나머지는
    `... (N more bytes)`로 줄입니다. 그 밖의 본문은 `[body N bytes, binary]` 한 줄입니다.
  - 본문은 프록시가 최대 1MB까지만 수집합니다(초과하면 안내 문구로 대체됨).
  - WebSocket 프레임은 프레임마다 한 줄로 즉시 출력합니다(텍스트는 앞 200자까지).
  - 중계 오류 등으로 본문이 끝내 완료되지 않으면, 60초 뒤 확보된 내용만으로
    `(incomplete)` 표시를 붙여 출력합니다.

### 4. `java -jar`로 직접 실행

`./gradlew installDist` 없이, 위 "빌드 결과물"의 `oe-proxy-0.1.0-all.jar` 하나만으로도 동일하게
동작합니다 (slf4j-simple을 포함한 모든 의존성이 이미 jar 안에 들어 있습니다):

```bash
java -Djava.net.preferIPv4Stack=true -jar build/libs/oe-proxy-0.1.0-all.jar ca ./conf
java -Djava.net.preferIPv4Stack=true -jar build/libs/oe-proxy-0.1.0-all.jar run ./conf/routes.sample.yml
```

`-Djava.net.preferIPv4Stack=true`는 `installDist`로 만든 시작 스크립트가 항상 전달하는 옵션과
동일하며, 이 CLI로 직접 실행할 때도 권장되는 JVM 옵션입니다.
