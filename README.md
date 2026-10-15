# camalert

차량 Bluetooth가 연결되면 백그라운드에서 자동으로 시작해, 전방 단속 카메라를 카카오내비 음성으로 알려주는 Android 앱입니다. 화면을 볼 필요 없이 차량 스피커로 안내합니다.

- 차량 Bluetooth 연결 시 자동 시작, 연결 해제 시 자동 종료
- 알림창에서 멈춤/시작, 종료, 설정
- 설정 화면: 권한 상태 확인, 차량으로 쓸 Bluetooth 기기 선택

카메라 정보와 안내 음성은 카카오내비 SDK 안전운행 모드를 씁니다. 안내 항목, 안내 시작 거리, 과속 경고음, 음량은 설정 화면에서 바꿀 수 있습니다.

## 빌드

`main`에 push하면 GitHub Actions가 테스트 후 APK를 빌드해 Artifact로 올립니다.
서명 키는 저장소 Secrets(`CAMALERT_KEYSTORE_B64`, `CAMALERT_KEYSTORE_PASSWORD`, `CAMALERT_KEY_ALIAS`, `CAMALERT_KEY_PASSWORD`)로 주입합니다. 키가 바뀌면 폰에서 덮어쓰기 업데이트가 안 되니 키 파일을 잘 보관하세요.
