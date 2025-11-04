#!/usr/bin/env bash
set -euo pipefail

# Build and test script for Polynote CI
# Usage: ./build-and-test.sh <scala-version>
# Example: ./build-and-test.sh 2.12.12

if [ $# -ne 1 ]; then
    echo "Usage: $0 <scala-version>"
    echo "Example: $0 2.12.12"
    exit 1
fi

SCALA_VERSION="$1"

echo "==================================="
echo "Building Polynote with Scala ${SCALA_VERSION}"
echo "==================================="

# Set up Python dependencies using uv
echo "Setting up Python dependencies with uv"

# Install uv if not already installed
if ! command -v uv &> /dev/null; then
    echo "Installing uv..."
    curl -LsSf https://astral.sh/uv/install.sh | sh
    # Source the environment to make uv available
    export PATH="$HOME/.cargo/bin:$PATH"
fi

# Install Python dependencies using uv
echo "Installing Python dependencies from requirements.txt..."
# Create a Python virtual environment if it doesn't exist
if [ ! -d ".venv" ]; then
    echo "Creating Python virtual environment in .venv..."
    python -m venv .venv
fi

# Activate the virtual environment
echo "Activating Python virtual environment..."
source .venv/bin/activate

# Install Python dependencies into the virtual environment using uv
echo "Installing Python dependencies from requirements.txt into venv..."
uv pip install -r ./requirements.txt

# Get jep installation details
echo "Configuring jep library paths..."
jep_site_packages_path=$(uv pip show jep | grep "^Location:" | cut -d ':' -f 2 | xargs)
jep_path="${jep_site_packages_path}/jep"
jep_lib_path=$(realpath "${jep_site_packages_path}/../../")

# Set up environment variables for jep
export LD_LIBRARY_PATH="${jep_path}:${jep_site_packages_path}:${jep_lib_path}:${LD_LIBRARY_PATH:-}"
export LD_PRELOAD="${jep_lib_path}/libpython3.so"

echo "LD_LIBRARY_PATH: ${LD_LIBRARY_PATH}"
echo "LD_PRELOAD: ${LD_PRELOAD}"

# Run sbt tests
echo "Running sbt tests with Scala ${SCALA_VERSION}..."
sbt "set scalaVersion := \"${SCALA_VERSION}\"" test

echo "==================================="
echo "Build and test completed successfully!"
echo "==================================="
