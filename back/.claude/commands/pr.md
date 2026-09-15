---
description: 현재 브랜치 분석해서 main 대상 PR 생성 URL 출력
allowed-tools: Bash, Read
argument-hint: [추가 컨텍스트]
---

현재 브랜치의 변경사항을 분석해서 main으로 향하는 PR을 준비하고, 미리 채워진 GitHub PR 생성 URL을 출력해.
PR 본문은 반드시 `.github/PULL_REQUEST_TEMPLATE.md` 형식을 그대로 따른다 (섹션 임의 추가/삭제/재구성 금지).

## 사전 점검 (실패 시 즉시 중단하고 한국어로 안내)

1. `git rev-parse --is-inside-work-tree` — git 레포가 아니면 중단
2. `git branch --show-current` — 결과가 `main`이면 즉시 중단 ("main에서는 PR 만들 수 없습니다")
3. `git status --short` — 미커밋/미스테이징 변경 있으면 중단 ("커밋 먼저 해주세요")
4. `git fetch origin main` 후 `git log origin/main..HEAD --oneline` — 새 커밋 0개면 중단

## 변경사항 분석

- `git log origin/main..HEAD --oneline` — 커밋 목록
- `git diff origin/main...HEAD --stat` — 변경 파일/규모
- 필요 시 `git diff origin/main...HEAD` 일부 확인
- 현재 브랜치명이 `feat/N`, `fix/N`처럼 `타입/숫자` 패턴이면 숫자를 이슈 번호 후보로 추정. 커밋 메시지 끝에 `#N`이 붙어 있으면 그걸 우선으로 사용.

추가 컨텍스트: $ARGUMENTS

## PR 제목 작성 규칙

이 저장소의 기존 커밋 컨벤션(`git log --oneline`으로 직접 확인)을 그대로 따른다:
- `[type] 설명` 형식, 이모지 없음
- type은 소문자 (`feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `ci`, `perf`, `style`) 중 diff 내용에 맞는 것 선택
- 이슈 번호를 찾았으면 제목 끝에 ` #N` 추가 (예: `[refactor] 결제 락 범위 축소 #12`)
- 여러 커밋이 섞여 있으면 가장 핵심 변경 1개를 대표로 선택

## PR 본문 작성 규칙

1. `Read`로 `.github/PULL_REQUEST_TEMPLATE.md`를 먼저 읽는다.
2. 그 템플릿의 섹션 구조·체크박스를 그대로 유지한 채 내용만 채운다:
   - **관련 이슈**: 이슈 번호를 찾았으면 `Closes #N`, 못 찾았으면 빈 칸으로 둔다.
   - **변경 사항**: 커밋 로그/diff에서 무엇을·왜 바꿨는지 요약.
   - **AI 활용 범위**: 이번 세션 대화에서 Claude가 생성/수정한 코드가 diff에 포함돼 있으면 해당 체크박스를 켜고 범위·검증 방법을 구체적으로 채운다. 전부 사람이 직접 작성한 코드라면 "직접 작성한 코드만 포함됨"을 켠다. **확인 안 되면 추측으로 체크하지 않는다.**
   - **테스트**: diff에 실제로 테스트 파일이 포함돼 있는지 확인한 뒤에만 해당 체크박스를 켠다. 동시성 시나리오 테스트는 `ExecutorService`/jqwik 기반 테스트가 diff에 있을 때만 체크.
   - **스크린샷 / 리뷰어에게**: 내용을 지어내지 말고 빈 칸으로 둔다 — 사용자가 직접 채우도록.

## 실행 단계

1. **브랜치 push**: `git push -u origin <현재브랜치>`
2. **레포 정보 추출**: `git remote get-url origin` 결과에서 `owner/repo` 파싱
   - SSH 형식 `git@github.com:owner/repo.git` → `owner/repo`
   - HTTPS 형식 `https://github.com/owner/repo.git` → `owner/repo`
   - 끝의 `.git` 접미사 제거
3. **URL 인코딩** (python3 사용):
   ```bash
   python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "<텍스트>"
   ```
4. **PR 생성 URL 조합**:
   ```
   https://github.com/{owner}/{repo}/compare/main...{현재브랜치}?expand=1&title={인코딩된제목}&body={인코딩된본문}
   ```
5. **출력 형식**:
   ```
   ✅ 브랜치 push 완료: <현재브랜치> → main

   PR 제목:
   <제목>

   PR 본문 미리보기:
   ---
   <본문>
   ---

   아래 URL 클릭하면 PR 생성 페이지가 열립니다:
   <URL>
   ```
   - 항상 출력 맨 마지막에 머지 후 로컬 동기화 안내 추가:
     ```
     PR 머지 후 로컬 동기화:
     git checkout main && git pull
     ```

## 절대 하지 말 것

- 자동으로 PR을 생성/머지하지 말 것 — 사용자가 URL 클릭해서 직접 Create 버튼 눌러야 함
- main 브랜치에서 이 커맨드를 실행하면 즉시 중단
- `.github/PULL_REQUEST_TEMPLATE.md`의 섹션을 임의로 추가/삭제/재구성하지 않는다
- "AI 활용 범위"/"테스트" 체크박스를 실제 diff 확인 없이 추측으로 체크하지 않는다
