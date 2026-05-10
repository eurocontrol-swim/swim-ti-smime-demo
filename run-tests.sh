#!/bin/bash
# ============================================================================
# SWIM S/MIME Demo - Build, Self-Test, and Cross-Language Scenario Runner
# ============================================================================
#
# Prerequisites:
#   - Java 17 (Eclipse Temurin), Python 3.12, .NET 9, Maven
#   - ActiveMQ Artemis broker running on localhost:5672 (for AMQP scenarios)
#   - Certificates generated in certs/ (run certs/generate-certs.sh first)
#
# Usage:
#   bash run-tests.sh              # Run everything (build + self-tests + scenarios)
#   bash run-tests.sh --no-amqp    # Skip AMQP scenarios (no broker needed)
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Environment ──
# These can be overridden by setting them before running the script.
# Defaults assume java, mvn, python, and dotnet are on PATH.
if [ -n "$JAVA_HOME" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
PYTHON="${PYTHON:-python}"
# Verify required tools
for cmd in java mvn "$PYTHON" dotnet; do
    if ! command -v "$cmd" > /dev/null 2>&1; then
        echo "ERROR: '$cmd' not found on PATH. Set JAVA_HOME / PYTHON or add tools to PATH."
        exit 1
    fi
done

SKIP_AMQP=false
if [[ "$1" == "--no-amqp" ]]; then
    SKIP_AMQP=true
fi

PASS=0
FAIL=0
PIDS_TO_KILL=()

killtree() {
    local pid=$1
    # Kill the process; on Windows/MSYS also kill direct children
    local children
    children=$(wmic process where "ParentProcessId=$pid" get ProcessId 2>/dev/null | grep -E '^[0-9]+' | tr -d '\r') || true
    for cpid in $children; do
        taskkill //pid "$cpid" //f > /dev/null 2>&1 || kill "$cpid" 2>/dev/null || true
    done
    kill "$pid" 2>/dev/null || taskkill //pid "$pid" //f > /dev/null 2>&1 || true
    wait "$pid" 2>/dev/null || true
}

cleanup() {
    for pid in "${PIDS_TO_KILL[@]}"; do
        killtree "$pid"
    done
    PIDS_TO_KILL=()
}
trap cleanup EXIT

pass() {
    echo "  [PASS] $1"
    ((PASS++))
}

fail() {
    echo "  [FAIL] $1"
    ((FAIL++))
}

echo "============================================================================"
echo "  SWIM S/MIME Demo - Test Suite"
echo "============================================================================"
echo ""

# ── 1. BUILD ──
echo "── Phase 1: Build ──────────────────────────────────────────────────────────"

echo "  Building Java module..."
cd "$SCRIPT_DIR/java"
if mvn compile -q 2>&1; then
    pass "Java compile"
else
    fail "Java compile"
fi

echo "  Building C# module..."
cd "$SCRIPT_DIR/csharp"
dotnet build --nologo 2>&1 | tail -5
if [ ${PIPESTATUS[0]} -eq 0 ]; then
    pass "C# compile"
else
    fail "C# compile"
fi
# Run via DLL to avoid security policies that may block locally-built .exe files
CSHARP_DLL="$SCRIPT_DIR/csharp/bin/Debug/net9.0/SwimSmimeDemo.dll"

echo "  Checking Python dependencies..."
cd "$SCRIPT_DIR"
if "$PYTHON" -c "from smime_helper import *; from flask import Flask" 2>/dev/null; then
    pass "Python dependencies"
else
    # Try with path
    if "$PYTHON" -c "import sys; sys.path.insert(0,'python'); from smime_helper import *; from flask import Flask" 2>/dev/null; then
        pass "Python dependencies"
    else
        fail "Python dependencies"
    fi
fi
echo ""

# ── 2. SELF-TESTS ──
echo "── Phase 2: Self-Tests ───────────────────────────────────────────────────"

echo "  Running Java self-test..."
cd "$SCRIPT_DIR/java"
if mvn -q exec:java -Dexec.mainClass="com.swim.smime.SelfTest" -Dexec.args="../certs" 2>&1 | grep -q "ALL JAVA S/MIME TESTS PASSED"; then
    pass "Java S/MIME self-test"
else
    fail "Java S/MIME self-test"
fi

echo "  Running Python self-test..."
cd "$SCRIPT_DIR/python"
if "$PYTHON" self_test.py ../certs 2>&1 | grep -q "ALL PYTHON S/MIME TESTS PASSED"; then
    pass "Python S/MIME self-test"
else
    fail "Python S/MIME self-test"
fi

echo "  Running C# self-test..."
cd "$SCRIPT_DIR/csharp"
if dotnet "$CSHARP_DLL" test ../certs 2>&1 | grep -q "ALL C# S/MIME TESTS PASSED"; then
    pass "C# S/MIME self-test"
else
    fail "C# S/MIME self-test"
fi
cd "$SCRIPT_DIR"
echo ""

# ── 3. REST SCENARIOS ──
echo "── Phase 3: REST Scenarios (WS Light with Message Security) ─────────────"

# Scenario 1: Java Producer -> Python Consumer (sign only)
echo "  Scenario 1: Java -> Python (REST, sign only)"
cd "$SCRIPT_DIR/python"
"$PYTHON" -u rest_consumer.py > /tmp/swim_rest_pyconsumer.log 2>&1 &
PID=$!; PIDS_TO_KILL+=($PID)
sleep 3

cd "$SCRIPT_DIR/java"
OUTPUT=$(mvn -q exec:java -Dexec.mainClass="com.swim.smime.RestProducer" \
    -Dexec.args="../certs ../payload/sample-flight.json http://localhost:5000/receive sign" 2>&1)
if echo "$OUTPUT" | grep -q "Response status: 200"; then
    pass "Scenario 1: Java->Python REST sign"
else
    fail "Scenario 1: Java->Python REST sign"
    echo "$OUTPUT" | tail -3
fi

killtree $PID; PIDS_TO_KILL=()
sleep 1

# Scenario 2: Python Producer -> C# Consumer (sign+encrypt)
echo "  Scenario 2: Python -> C# (REST, sign+encrypt)"
cd "$SCRIPT_DIR/csharp"
dotnet "$CSHARP_DLL" rest-consumer ../certs 8443 > /tmp/swim_rest_csharpconsumer.log 2>&1 &
PID=$!; PIDS_TO_KILL+=($PID)
sleep 5

cd "$SCRIPT_DIR/python"
OUTPUT=$("$PYTHON" rest_producer.py --url http://localhost:8443/receive --mode sign-encrypt 2>&1)
if echo "$OUTPUT" | grep -q "Response status: 200"; then
    pass "Scenario 2: Python->C# REST sign+encrypt"
else
    fail "Scenario 2: Python->C# REST sign+encrypt"
    echo "$OUTPUT" | tail -3
fi

killtree $PID; PIDS_TO_KILL=()
sleep 1
cd "$SCRIPT_DIR"
echo ""

# ── 4. AMQP SCENARIOS ──
if $SKIP_AMQP; then
    echo "── Phase 4: AMQP Scenarios (skipped with --no-amqp) ─────────────────────"
    echo ""
else
    echo "── Phase 4: AMQP Scenarios (AMQP with Message Security) ─────────────────"

    # Check broker is running
    if ! curl -s -o /dev/null http://localhost:8161/ 2>/dev/null; then
        echo "  ERROR: ActiveMQ Artemis broker not running on localhost:5672"
        echo "  Start it with: broker/swim-broker/bin/artemis run"
        echo "  Skipping AMQP scenarios."
        fail "Scenario 3: C#->Java AMQP sign (broker not running)"
        fail "Scenario 4: Java->Python AMQP sign+encrypt (broker not running)"
    else
        # Scenario 3: C# Producer -> Java Consumer (sign only)
        echo "  Scenario 3: C# -> Java (AMQP, sign only)"

        cd "$SCRIPT_DIR/java"
        mvn -q exec:java -Dexec.mainClass="com.swim.smime.AmqpConsumer" \
            -Dexec.args="../certs localhost 5672 swim.flight.data" > /tmp/swim_amqp_javaconsumer.log 2>&1 &
        PID=$!; PIDS_TO_KILL+=($PID)
        sleep 5

        cd "$SCRIPT_DIR/csharp"
        dotnet "$CSHARP_DLL" amqp-producer ../certs ../payload/sample-flight.json \
            amqp://admin:admin@localhost:5672 swim.flight.data sign > /dev/null 2>&1

        # Poll log file for result (up to 15 seconds)
        for i in $(seq 1 15); do
            if grep -q "Signature VERIFIED successfully" /tmp/swim_amqp_javaconsumer.log 2>/dev/null; then
                break
            fi
            sleep 1
        done

        if grep -q "Signature VERIFIED successfully" /tmp/swim_amqp_javaconsumer.log 2>/dev/null; then
            pass "Scenario 3: C#->Java AMQP sign"
        else
            fail "Scenario 3: C#->Java AMQP sign"
            cat /tmp/swim_amqp_javaconsumer.log 2>/dev/null | tail -5
        fi

        killtree $PID; PIDS_TO_KILL=()
        sleep 1

        # Scenario 4: Java Producer -> Python Consumer (sign+encrypt)
        echo "  Scenario 4: Java -> Python (AMQP, sign+encrypt)"

        cd "$SCRIPT_DIR/python"
        "$PYTHON" -u amqp_consumer.py --broker "localhost:5672" --queue "swim.flight.data" \
            > /tmp/swim_amqp_pyconsumer.log 2>&1 &
        PID=$!; PIDS_TO_KILL+=($PID)
        sleep 8

        cd "$SCRIPT_DIR/java"
        mvn -q exec:java -Dexec.mainClass="com.swim.smime.AmqpProducer" \
            -Dexec.args="../certs ../payload/sample-flight.json localhost 5672 swim.flight.data sign-encrypt" \
            > /dev/null 2>&1

        # Poll log file for result (up to 15 seconds)
        for i in $(seq 1 15); do
            if grep -q "DECRYPTED and signature VERIFIED" /tmp/swim_amqp_pyconsumer.log 2>/dev/null; then
                break
            fi
            sleep 1
        done

        if grep -q "DECRYPTED and signature VERIFIED" /tmp/swim_amqp_pyconsumer.log 2>/dev/null; then
            pass "Scenario 4: Java->Python AMQP sign+encrypt"
        else
            fail "Scenario 4: Java->Python AMQP sign+encrypt"
            cat /tmp/swim_amqp_pyconsumer.log 2>/dev/null | tail -5
        fi

        killtree $PID; PIDS_TO_KILL=()
    fi
    cd "$SCRIPT_DIR"
    echo ""
fi

# ── SUMMARY ──
echo "============================================================================"
TOTAL=$((PASS + FAIL))
echo "  Results: $PASS/$TOTAL passed, $FAIL failed"
if [ "$FAIL" -eq 0 ]; then
    echo "  ALL TESTS PASSED"
else
    echo "  SOME TESTS FAILED"
fi
echo "============================================================================"

exit $FAIL
