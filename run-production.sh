#!/bin/bash
# ============================================
# Script de execução - PRODUÇÃO
# ============================================

set -e

echo "=========================================="
echo "Migrador IIRGD - Production Mode"
echo "=========================================="

# Cores
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21}
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
JAR_FILE="$APP_HOME/migrador-app/target/migrador-app-1.0.0.jar"
JVM_OPTIONS="$APP_HOME/jvm-production.options"
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
echo "JAR File: $JAR_FILE"
echo "JVM Options: $JVM_OPTIONS"
echo ""

# Verificar se JAR existe
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found. Run 'mvn clean package' first."
    exit 1
fi

# Ler opções JVM do arquivo
JVM_OPTS=""
while IFS= read -r line; do
    # Ignorar comentários e linhas vazias
    [[ "$line" =~ ^#.*$ || -z "$line" ]] && continue
    JVM_OPTS="$JVM_OPTS $line"
done < "$JVM_OPTIONS"

echo "Starting application with optimized JVM settings..."
echo "=========================================="
echo ""

# Executar aplicação
exec "$JAVA_HOME/bin/java" $JVM_OPTS \
    -jar "$JAR_FILE" \
    "$@"
