#!/bin/bash
# ============================================
# Script de execução - DESENVOLVIMENTO
# ============================================

set -e

echo "=========================================="
echo "Migrador IIRGD - Development Mode"
echo "=========================================="

# Cores
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21}
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
JAR_FILE="$APP_HOME/migrador-app/target/migrador-app-1.0.0.jar"
JVM_OPTIONS="$APP_HOME/jvm-development.options"
LOGS_DIR="$APP_HOME/logs"

# Criar diretório de logs
mkdir -p "$LOGS_DIR"

# Verificar Java 21
JAVA_VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 21 ]; then
    echo "ERROR: Java 21+ required. Current: $JAVA_VERSION"
    exit 1
fi

echo "Java Version: $JAVA_VERSION"
echo "Java Home: $JAVA_HOME"
echo "App Home: $APP_HOME"
echo ""

# Ler opções JVM do arquivo
JVM_OPTS=""
while IFS= read -r line; do
    # Ignorar comentários e linhas vazias
    [[ "$line" =~ ^#.*$ || -z "$line" ]] && continue
    JVM_OPTS="$JVM_OPTS $line"
done < "$JVM_OPTIONS"

echo "Using Maven Spring Boot plugin with dev settings..."
echo "=========================================="
echo ""

# Executar via Maven (hot reload habilitado)
cd "$APP_HOME"
exec mvn spring-boot:run -pl migrador-app \
    -Dspring-boot.run.jvmArguments="$JVM_OPTS" \
    "$@"
