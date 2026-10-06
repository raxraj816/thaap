#!/usr/bin/env bash
set -euo pipefail

# Real container replacement proof against its own fresh Kafka volume. Not EFS/HA certification.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="thaap-broker-proof-$$"
compose() {
    THAAP_KAFKA_PORT=59093 docker compose -p "$PROJECT" -f "$ROOT_DIR/infra/docker/compose.yml" "$@"
}
cleanup() { compose down -v >/dev/null 2>&1 || true; }
for resource in container volume network; do
    case "$resource" in
        container) existing="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")" ;;
        volume) existing="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")" ;;
        network) existing="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT")" ;;
    esac
    [[ -z "$existing" ]] || { echo "FAIL: occupied broker proof namespace" >&2; exit 1; }
done
trap cleanup EXIT

wait_broker() {
    for _ in $(seq 1 60); do
        if compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    echo "FAIL: broker did not start" >&2
    return 1
}
consume() {
    compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server localhost:9092 --topic durable-fixture --group durable-reader \
        --from-beginning --max-messages 1 --timeout-ms 20000 \
        --consumer-property auto.commit.interval.ms=100
}
offset() {
    compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 --group durable-reader --describe 2>/dev/null \
        | awk '$2 == "durable-fixture" {print $4}'
}

compose up -d kafka
wait_broker
broker="$(compose ps -q kafka)"
[[ "$(docker inspect --format '{{.HostConfig.Memory}}' "$broker")" == "1073741824" ]]
compose exec -T kafka sh -c 'ps -o args | grep -F -- "-Xms512m -Xmx512m"' >/dev/null
compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --topic durable-fixture --partitions 1 --replication-factor 1
printf 'before-replacement\n' | compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic durable-fixture
[[ "$(consume)" == "before-replacement" ]]
[[ "$(offset)" == "1" ]]
before="$(compose ps -q kafka)"
compose rm --stop --force kafka
compose up -d kafka
wait_broker
after="$(compose ps -q kafka)"
[[ "$before" != "$after" ]]
[[ "$(offset)" == "1" ]]
printf 'after-replacement\n' | compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic durable-fixture
[[ "$(consume)" == "after-replacement" ]]
[[ "$(offset)" == "2" ]]
retained="$(compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic durable-fixture --from-beginning \
    --max-messages 2 --timeout-ms 20000 2>/dev/null)"
[[ "$retained" == $'before-replacement\nafter-replacement' ]]
echo "PASS: replacing the broker retained both records and consumer offsets 1 -> 2"
