#!/usr/bin/env bash
# 측정 밤을 연다 (measure/k6-load). 서버 + 부하기 두 대를 띄우고, 서버는 부트스트랩·계정 풀·앱 기동까지,
# 부하기는 k6 설치·journey.js 복사까지. 끝나면 run-stage.sh 가 읽을 env 파일을 찍는다.
#
#   NIGHT=18 ./night-up.sh            → measure/k6-load/.night18.env  (SERVER, SERVER_PRIV, GEN, IDS)
#   source measure/k6-load/.night18.env && ./run-stage.sh f40 SCENARIO=rate RATE=40 DURATION=90s
#
# 밤 15~17은 이 순서를 손으로 쳤다. 끝나면 반드시 terminate 한다 (AGENTS.md — 정지해 둔 인스턴스가 EBS째 사라진 적이 있다):
#   aws ec2 terminate-instances --instance-ids $IDS
set -euo pipefail
NIGHT=${NIGHT:?}
REGION=${REGION:-ap-northeast-2}
AMI=${AMI:-$(aws ec2 describe-images --region $REGION --owners amazon \
      --filters "Name=name,Values=al2023-ami-2023*-kernel-6.1-x86_64" "Name=state,Values=available" \
      --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text)}
SERVER_TYPE=${SERVER_TYPE:-m7i.2xlarge}
GEN_TYPE=${GEN_TYPE:-c7i.xlarge}
SG=${SG:-sg-0382132d2f8b5f6ce}          # shadowfit-measure-sg: 운영자 IP에서 22/80/8080, SG 안끼리는 전부
SUBNET=${SUBNET:-subnet-0f22e466008d27262} # ap-northeast-2a. 두 대를 같은 서브넷에 — 부하기는 사설 IP로 친다
KEY_NAME=${KEY_NAME:-shadowfit-measure}
KEY=${KEY:-$HOME/.ssh/shadowfit-measure.pem}
REPO_URL=${REPO_URL:-https://github.com/DOCKin-project/DOCKin-backend.git}
BRANCH=${BRANCH:-main}
SSH="ssh -i $KEY -o ConnectTimeout=15 -o StrictHostKeyChecking=no -o LogLevel=ERROR"
HERE=$(cd "$(dirname "$0")" && pwd)
ENVF=$HERE/.night$NIGHT.env

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

launch() { # <type> <name> <disk GB>
  aws ec2 run-instances --region $REGION --image-id "$AMI" --instance-type "$1" --key-name "$KEY_NAME" \
    --security-group-ids "$SG" --subnet-id "$SUBNET" \
    --block-device-mappings "[{\"DeviceName\":\"/dev/xvda\",\"Ebs\":{\"VolumeSize\":$3,\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true}}]" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$2}]" \
    --query 'Instances[0].InstanceId' --output text
}

say "띄운다: $SERVER_TYPE(서버) + $GEN_TYPE(부하기), $AMI"
SID=$(launch $SERVER_TYPE night$NIGHT-server 30)
GID=$(launch $GEN_TYPE night$NIGHT-gen 16)
aws ec2 wait instance-running --region $REGION --instance-ids $SID $GID
read -r SERVER SERVER_PRIV <<<"$(aws ec2 describe-instances --region $REGION --instance-ids $SID --query 'Reservations[0].Instances[0].[PublicIpAddress,PrivateIpAddress]' --output text)"
GEN=$(aws ec2 describe-instances --region $REGION --instance-ids $GID --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
cat > "$ENVF" <<E
export SERVER=$SERVER SERVER_PRIV=$SERVER_PRIV GEN=$GEN IDS="$SID $GID" REGION=$REGION
E
echo "   server $SID $SERVER ($SERVER_PRIV) / gen $GID $GEN → $ENVF"

for h in $SERVER $GEN; do
  for i in $(seq 1 40); do $SSH ec2-user@$h true 2>/dev/null && break; sleep 5; done
  $SSH ec2-user@$h true || { echo "ssh 실패: $h"; exit 1; }
done

say "부하기: k6"
$SSH ec2-user@$GEN 'sudo dnf install -y -q https://dl.k6.io/rpm/repo.rpm >/dev/null 2>&1 || true; sudo dnf install -y -q k6 >/dev/null; mkdir -p out; k6 version; echo "* soft nofile 1048576" | sudo tee -a /etc/security/limits.conf >/dev/null; echo "* hard nofile 1048576" | sudo tee -a /etc/security/limits.conf >/dev/null; sudo sysctl -q -w net.ipv4.ip_local_port_range="1024 65535" net.ipv4.tcp_tw_reuse=1' &
scp -q -i "$KEY" -o StrictHostKeyChecking=no "$HERE"/*.js ec2-user@$GEN:~/ &

say "서버: 부트스트랩 (클론·빌드·DB·스키마) — 10분쯤"
$SSH ec2-user@$SERVER "sudo dnf install -y -q git >/dev/null; git clone -q --depth 50 -b $BRANCH $REPO_URL DOCKin-spring && cd DOCKin-spring && REPO_URL=$REPO_URL bash scripts/aws-bootstrap.sh 2>&1 | tail -25"

# 서버는 main을 클론했다. 워킹트리에만 있는 측정 파일(밤 18에 nginx-keepalive.conf가 없어 한 단계를 버렸다)을 덮어쓴다.
say "서버: measure/k6-load 워킹트리 사본 덮어쓰기"
scp -q -i "$KEY" -o StrictHostKeyChecking=no "$HERE"/*.sh "$HERE"/*.sql "$HERE"/*.js "$HERE"/*.conf ec2-user@$SERVER:DOCKin-spring/measure/k6-load/

say "서버: 계정 4만 + 작업일지 12만 + 채팅방 400, 앱 기동 (compose.k6mem.yaml — 기동 색인 끔)"
$SSH ec2-user@$SERVER 'cd DOCKin-spring && C="docker compose -f compose.yaml -f compose.gc.yaml -f compose.k6mem.yaml"; $C up -d dockin-app dockin-nginx >/dev/null 2>&1;
  for i in $(seq 1 90); do [[ "$(docker inspect -f "{{.State.Health.Status}}" dockin-app-1 2>/dev/null)" == healthy ]] && break; sleep 3; done;
  docker inspect -f "app {{.State.Health.Status}}" dockin-app-1;
  $C exec -T DOCKin-DB psql -U root -d dockindb -qtAX < measure/k6-load/seed-users.sql 2>&1 | tail -2;
  $C exec -T DOCKin-DB psql -U root -d dockindb -qtAX < measure/k6-load/seed-chat-rooms.sql 2>&1 | tail -1;
  chmod +x measure/k6-load/*.sh; docker ps --format "{{.Names}} {{.Status}}"'
wait

say "연결 확인: 부하기 → 서버 nginx:80"
$SSH ec2-user@$GEN "curl -s -m 5 -o /dev/null -w 'login %{http_code} %{time_total}s\n' -H 'Content-Type: application/json' -d '{\"userId\":\"k6u00001\",\"password\":\"dockin1234\"}' http://$SERVER_PRIV/api/member/login"
echo; echo "다음: source $ENVF && ./run-stage.sh <label> ...   끝나면 aws ec2 terminate-instances --instance-ids \$IDS"
