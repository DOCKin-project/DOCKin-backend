FROM amazoncorretto:21-alpine-jdk
RUN apk add --no-cache ffmpeg
COPY build/libs/DOCKin-spring-0.0.1-SNAPSHOT.jar app.jar
# 힙 상한을 400M로 내렸다. 이전 512M은 compose의 컨테이너 메모리 제한과 정확히 같았고,
# JVM은 힙 밖에도 메모리를 쓴다(메타스페이스, 스레드 스택, 다이렉트 버퍼, 코드 캐시).
# 즉 힙을 상한까지 채우는 것만으로 컨테이너 제한을 넘어 OOM Killer에 죽는다.
# 그때 나오는 것은 자바 예외가 아니라 종료코드 137이라 원인이 로그에 남지 않는다.
#
# JAVA_OPTS를 실제로 읽게 했다. compose.yaml에 이 변수가 선언되어 있었지만
# exec 형식 ENTRYPOINT는 셸을 거치지 않으므로 값이 확장되지 않았다 -- 선언만 있고
# 적용된 적이 없다(백로그 P2-10-1). 여기 적힌 값은 이제 기본값이고, 환경변수가 있으면
# 그쪽이 이긴다(자바는 같은 옵션이 중복되면 뒤에 온 것을 쓴다).
#
# exec를 붙인 이유 -- 없으면 셸이 PID 1로 남아 java가 SIGTERM을 못 받는다.
# 그러면 docker stop이 우아한 종료가 아니라 10초 뒤 강제 종료가 된다.
ENTRYPOINT ["sh", "-c", "exec java -Xmx400M -Xms256M $JAVA_OPTS -jar /app.jar"]