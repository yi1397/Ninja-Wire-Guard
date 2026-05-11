# Ninja WG

Ninja WG는 [wireguard-android](https://github.com/WireGuard/wireguard-android)를
기반으로 만든 Android WireGuard 클라이언트입니다.

- 앱 이름: `Ninja WG`
- 패키지 이름: `com.ninja.wireguard`
- 딥링크 스킴: `ninjawg://`

브라우저 딥링크로 WireGuard `.conf`를 바로 가져와 터널을 생성/갱신할 수 있고,
받는 즉시 활성화하거나 일정 시간이 지난 뒤 터널을 자동 삭제할 수 있습니다.

## 빌드

APK:

```sh
./gradlew :ui:assembleRelease
```

AAB:

```sh
./gradlew :ui:bundleRelease
```

기본 산출물 경로:

```text
ui/build/outputs/apk/release/
ui/build/outputs/bundle/release/
```

배포용 APK/AAB는 매 업데이트마다 같은 release key로 서명해야 합니다.

## 딥링크

지원하는 딥링크:

```text
ninjawg://import
ninjawg://apply
```

conf 전달 방식은 아래 중 하나를 사용합니다.

| 파라미터 | 설명 |
| --- | --- |
| `url` | URL 인코딩된 `.conf` 파일 주소 |
| `conf` | URL 인코딩된 WireGuard conf 원문 |
| `config` | `conf`와 동일 |
| `conf_b64` | URL-safe base64로 인코딩된 conf |
| `config_b64` | `conf_b64`와 동일 |
| URL fragment | `[Interface]`가 포함된 conf 원문 |

직접 스킴 예시:

```text
ninjawg://import?name=demo&url=https%3A%2F%2Fexample.com%2Fdemo.conf&delete_after=10m
```

브라우저/HTML 예시:

```html
<a href="intent://import?name=demo&url=https%3A%2F%2Fexample.com%2Fdemo.conf&delete_after=10m#Intent;scheme=ninjawg;package=com.ninja.wireguard;end">
  Ninja WG 적용
</a>
```

Android 브라우저에서는 `intent://...#Intent;...;end` 형식을 권장합니다.
패키지 이름을 직접 지정할 수 있어 다른 앱으로 열릴 가능성이 줄어듭니다.

## 딥링크 파라미터

### 터널 이름

아래 중 하나를 사용합니다.

```text
name=demo
tunnel=demo
profile=demo
```

이름이 없으면 기본값은 `wg`입니다.

이름의 특수문자는 `_`로 치환되고, `.conf` 확장자는 제거됩니다.

### 즉시 활성화

기본값은 `true`입니다. 즉, conf를 받으면 바로 터널을 켭니다.

즉시 활성화 끄기:

```text
activate=0
start=0
up=0
```

즉시 활성화 명시:

```text
activate=1
start=true
up=yes
```

true로 처리되는 값:

```text
1, true, yes, on
```

false로 처리되는 값:

```text
0, false, no, off
```

### 자동 삭제

지정한 시간이 지난 뒤 터널을 삭제합니다.

```text
delete_after=10m
delete_in=1h
ttl=7d
```

특정 시각에 삭제할 수도 있습니다.

```text
delete_at=2026-05-08T12:00:00Z
expires_at=2026-05-08T12:00:00Z
expires=1778236800
```

지원하는 시간 단위:

| 단위 | 의미 |
| --- | --- |
| `s`, `sec`, `secs`, `second`, `seconds` | 초 |
| `m`, `min`, `mins`, `minute`, `minutes` | 분 |
| `h`, `hr`, `hrs`, `hour`, `hours` | 시간 |
| `d`, `day`, `days` | 일 |

timestamp는 Unix seconds, Unix milliseconds, ISO-8601 UTC 문자열을 지원합니다.

## conf 추가 문법

Ninja WG 전용 메타데이터를 `.conf` 안에 주석으로 넣을 수 있습니다.
이 메타데이터 줄은 WireGuard 파싱 전에 제거되므로 실제 WireGuard conf는
정상 형식 그대로 유지됩니다.

예시:

```conf
# NinjaWG-Activate: true
# NinjaWG-Delete-After: 10m

[Interface]
PrivateKey = CLIENT_PRIVATE_KEY
Address = 10.66.66.2/32
DNS = 1.1.1.1

[Peer]
PublicKey = SERVER_PUBLIC_KEY
AllowedIPs = 10.66.66.1/32
Endpoint = vpn.example.com:51820
PersistentKeepalive = 25
```

지원하는 메타데이터:

```text
NinjaWG-Activate
NinjaWG-Up
NinjaWG-Start
NinjaWG-Delete-After
NinjaWG-Auto-Delete-After
NinjaWG-TTL
NinjaWG-Delete-At
NinjaWG-Expires-At
NinjaWG-Expires
```

`#`와 `;` 주석을 모두 지원합니다.

```conf
; NinjaWG-Delete-After: 30m
```

## 우선순위

딥링크 파라미터가 conf 내부 메타데이터보다 우선합니다.

예시:

```text
ninjawg://import?name=demo&url=https%3A%2F%2Fexample.com%2Fdemo.conf&activate=0&delete_after=30m
```

위 링크로 열면 conf 안에 `# NinjaWG-Activate: true`가 있어도 터널을 즉시
활성화하지 않습니다. 딥링크의 `activate=0`이 우선입니다.

## 전체 예시

HTML:

```html
<a href="intent://import?name=demo&url=https%3A%2F%2Fexample.com%2Fdemo.conf&activate=1&delete_after=30m#Intent;scheme=ninjawg;package=com.ninja.wireguard;end">
  30분 터널 적용
</a>
```

conf:

```conf
# NinjaWG-Activate: true
# NinjaWG-Delete-After: 30m

[Interface]
PrivateKey = CLIENT_PRIVATE_KEY
Address = 10.66.66.2/32

[Peer]
PublicKey = SERVER_PUBLIC_KEY
AllowedIPs = 0.0.0.0/0
Endpoint = vpn.example.com:51820
PersistentKeepalive = 25
```

## 참고

- `url` 값은 반드시 URL 인코딩해야 합니다.
- Android VPN 권한이 아직 없으면 앱이 권한 요청 화면을 띄웁니다.
- 터널은 켜졌는데 트래픽이 `RX 0 B`라면 딥링크 문제가 아니라 WireGuard
  핸드셰이크 문제입니다. 서버 peer 등록, public/private key, endpoint,
  방화벽, `AllowedIPs`를 확인해야 합니다.

## Upstream

이 프로젝트는 WireGuard for Android를 기반으로 합니다.
원본 프로젝트와 라이선스는 [wireguard-android](https://github.com/WireGuard/wireguard-android)를
참고하세요.
