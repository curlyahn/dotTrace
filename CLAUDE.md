# DotTrace 개발 원칙

## ⚠️ 지난 기록은 바뀌지 않는다 (가장 중요)

판단 기준(이동 수단 분류, 구간 나누기, 학습 규칙, 지하철역 확인, 튀는 위치 걸러내기 등)을 바꾸더라도
**지난 날짜의 기록(이동 수단·구간)은 바뀌면 안 된다.** 새 기준은 오늘 이후 기록에만 적용된다.

구현 방식 (`trips/DayAnalyzer.kt`의 `DayStore.autoSegments`):
- 지난 날짜는 처음 분석할 때 그때의 기준으로 **고정본**(`frozen_days` 표, 형식 태그 `f1`)을 만들고, 이후에는 고정본만 쓴다.
- 그날 기록 자체가 바뀐 경우(점 개수 변경: 가져오기 등)에만 고정본을 다시 만든다.
- 사용자가 직접 고친 것(탑승~하차·점 지정, 구간 수단 고치기, 머문 곳 이름)은 `DayAnalyzer.applyUser`로 고정본 위에 덧씌운다.
- 자동 보정(학습 경로·출발도착 규칙·지하철역 확인)은 `DayAnalyzer.autoModes` — 지난 날짜에는 고정본을 만들 때 한 번만 적용된다.

변경할 때 반드시 지킬 것:
- `frozen_days` 표를 마이그레이션에서 지우거나 비우지 않는다.
- 고정본 형식을 바꾸면 새 태그를 쓰되, 예전 태그도 계속 읽을 수 있게 `FROZEN_TAGS`에 남긴다.
- `CACHE_TAG`(day_cache)를 올리는 것은 괜찮다 — 오늘 기록과 아직 고정되지 않은 날에만 영향.
- 판단 로직을 바꾸는 작업은 지난 날짜가 고정본 경로(`autoSegments`/`applyUser`)를 타는지 확인한다.
  리포트(`stats/Report.kt`)와 지도(`MainViewModel.rawTrips/trips`) 모두.
- 주차 기록(`parkings`)·사용자 수정(overrides, mode_spans) 같은 저장 데이터도 기준 변경으로 다시 계산해 덮어쓰지 않는다.

## 그 밖의 관례
- 로컬에서 Gradle 빌드가 안 되므로 GitHub Actions로 빌드한다: `gh workflow run build.yml -R curlyahn/dotTrace --ref main` (릴리스 생성).
- UI 문구는 한국어, Dot 앱과 같은 다크 + 라임(연두) 스타일.
- 화면이 바뀌는 기능은 디자인 시안을 먼저 보여 주고 승인 후 구현한다.
- 앱 업데이트는 덮어쓰기 설치 (같은 서명 키) — 기록이 유지되어야 한다.
