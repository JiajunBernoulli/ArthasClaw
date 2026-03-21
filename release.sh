#!/bin/bash
# ArthasClaw One-Click Maven Release Script
# Usage: ./release.sh [new_version]
#
# This script:
# 1. Validates prerequisites (GPG, Maven, git)
# 2. Updates version number (optional)
# 3. Runs tests
# 4. Builds and deploys to Maven Central
# 5. Creates git tag and pushes

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
POM_FILE="$SCRIPT_DIR/agent/pom.xml"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Get current project version from pom.xml (only the project version, not dependency versions)
get_current_version() {
    xmllint --xpath "//*[local-name()='project']/*[local-name()='version']/text()" "$POM_FILE" 2>/dev/null || \
    grep -oP '(?<=<version>)[^<]+' "$POM_FILE" | head -1
}

# Update only project version in pom.xml (preserve dependency versions)
update_version() {
    local new_version="$1"
    # Use xmlstarlet or sed with precise matching
    if command -v xmlstarlet &> /dev/null; then
        xmlstarlet ed -P -N ns="http://maven.apache.org/POM/4.0.0" \
            -u "/ns:project/ns:version" -v "$new_version" "$POM_FILE" > "${POM_FILE}.tmp" && \
            mv "${POM_FILE}.tmp" "$POM_FILE"
    else
        # Fallback: only replace the first <version> tag (project version)
        sed -i "0,/<version>.*<\/version>/s/<version>.*<\/version>/<version>${new_version}<\/version>/" "$POM_FILE"
    fi
    log_success "Version updated to ${new_version}"
}

echo "=========================================="
echo "  ArthasClaw Maven Release Script"
echo "=========================================="
echo ""

# ============================================
# Step 1: Check prerequisites
# ============================================
log_info "Checking prerequisites..."

# Check git
if ! command -v git &> /dev/null; then
    log_error "git is not installed"
    exit 1
fi

# Check Maven
if ! command -v mvn &> /dev/null; then
    log_error "Maven is not installed"
    exit 1
fi
log_success "Maven: $(mvn -version | head -1)"

# Check GPG
if ! command -v gpg &> /dev/null; then
    log_error "GPG is not installed"
    exit 1
fi

# Check GPG key exists
if ! gpg --list-secret-keys 2>/dev/null | grep -q "sec"; then
    log_error "No GPG secret key found. Please generate one first:"
    echo "  gpg --full-generate-key"
    exit 1
fi
GPG_KEY_ID=$(gpg --list-secret-keys --keyid-format=short 2>/dev/null | grep sec | head -1 | awk '{print $2}' | cut -d'/' -f2)
log_success "GPG key: $GPG_KEY_ID"

# Check Maven settings for central credentials
MVN_SETTINGS="$HOME/.m2/settings.xml"
if [ ! -f "$MVN_SETTINGS" ]; then
    log_warn "Maven settings.xml not found at $MVN_SETTINGS"
    log_warn "Make sure you have configured 'central' server credentials"
fi

echo ""

# ============================================
# Step 2: Handle version update
# ============================================
CURRENT_VERSION=$(get_current_version)
log_info "Current version: $CURRENT_VERSION"

if [ -n "$1" ]; then
    NEW_VERSION="$1"
    log_info "Updating version to: $NEW_VERSION"
    update_version "$NEW_VERSION"
    CURRENT_VERSION="$NEW_VERSION"
else
    log_info "No version specified, keeping current version: $CURRENT_VERSION"
fi

echo ""

# ============================================
# Step 3: Check working directory status
# ============================================
log_info "Checking git status..."
cd "$SCRIPT_DIR"

if [ -n "$(git status --porcelain)" ]; then
    log_error "Working directory has uncommitted changes"
    echo ""
    git status -s
    echo ""
    log_error "Please commit or stash changes before releasing"
    exit 1
fi

# Check if we're on main branch
CURRENT_BRANCH=$(git branch --show-current)
if [ "$CURRENT_BRANCH" != "main" ] && [ "$CURRENT_BRANCH" != "master" ]; then
    log_warn "Not on main/master branch (currently: $CURRENT_BRANCH)"
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

log_success "Working directory is clean"
echo ""

# ============================================
# Step 4: Run tests
# ============================================
log_info "Running tests..."
cd "$SCRIPT_DIR/agent"

if ! mvn test -q; then
    log_error "Tests failed. Please fix before releasing."
    exit 1
fi
log_success "All tests passed"
echo ""

# ============================================
# Step 5: Deploy to Maven Central
# ============================================
log_info "Deploying to Maven Central..."
log_info "This may take a few minutes..."
echo ""

if ! mvn clean deploy -Prelease -DskipTests; then
    log_error "Deployment failed!"
    exit 1
fi

log_success "Deployment successful!"
echo ""

# ============================================
# Step 6: Commit version change (if any)
# ============================================
cd "$SCRIPT_DIR"

if [ -n "$1" ]; then
    log_info "Committing version change..."
    git add agent/pom.xml
    git commit -m "chore: release version $CURRENT_VERSION"
    log_success "Version change committed"
fi

# ============================================
# Step 7: Create git tag
# ============================================
TAG_NAME="v$CURRENT_VERSION"
log_info "Creating git tag: $TAG_NAME..."

if git tag -l | grep -q "^$TAG_NAME$"; then
    log_warn "Tag $TAG_NAME already exists"
    read -p "Overwrite? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        git tag -d "$TAG_NAME"
        git tag -a "$TAG_NAME" -m "Release $TAG_NAME"
        log_success "Tag $TAG_NAME overwritten"
    fi
else
    git tag -a "$TAG_NAME" -m "Release $TAG_NAME"
    log_success "Tag $TAG_NAME created"
fi

echo ""

# ============================================
# Step 8: Push to remote
# ============================================
log_info "Pushing to remote..."

if ! git push origin "$CURRENT_BRANCH" --tags; then
    log_error "Push failed!"
    exit 1
fi

log_success "Pushed to remote with tags"
echo ""

# ============================================
# Summary
# ============================================
echo "=========================================="
echo -e "${GREEN}  Release Complete!${NC}"
echo "=========================================="
echo ""
echo "Version:    $CURRENT_VERSION"
echo "Tag:        $TAG_NAME"
echo "Branch:     $CURRENT_BRANCH"
echo ""
echo "Maven Central: https://central.sonatype.com/publishing/deployments"
echo "GitHub Release: https://github.com/jiajunbernoulli/ArthasClaw/releases/new?tag=$TAG_NAME"
echo ""
log_info "It may take a few minutes for the package to appear on Maven Central."
log_info "Once available, users can use: https://repo1.maven.org/maven2/io/github/jiajunbernoulli/arthas-claw/${CURRENT_VERSION}/"
