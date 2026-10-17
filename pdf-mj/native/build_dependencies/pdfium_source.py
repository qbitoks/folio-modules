import os
import shutil
import subprocess
import time

from build_dependencies.common import DOWNLOAD_RETRY_DELAYS, delete_file_if_exists, error, get_lib_path, log
from build_dependencies.values import (
    ALL_ARCHES,
    ARCH_NAMES,
    DEPOT_TOOLS_GIT_URL,
    DEPOT_TOOLS_REVISION,
    FILE_NAMES,
    Lib,
    LIB_EXTENSION,
    PDFIUM_GIT_URL,
    PDFIUM_GN_ARGS,
    PDFIUM_GN_CPU_NAMES,
    PDFIUM_REVISION,
    PDFIUM_SOURCE_DIR,
)

DEPOT_TOOLS_DIR = "depot_tools"

PDFIUM_PATCHES = [
    (os.path.join("patches", "shared_library.patch"), "."),
    (os.path.join("patches", "public_headers.patch"), "."),
    (os.path.join("patches", "clang_rt.patch"), "build"),
    (os.path.join("patches", "android", "build.patch"), "build"),
]


def build_pdfium_from_source(arches):
    root = os.getcwd()
    env = depot_tools_env(root)
    source = sync_pdfium(root, env)
    for arch in arches:
        build_arch(source, arch, env)


def depot_tools_env(root):
    depot_tools = os.path.join(root, DEPOT_TOOLS_DIR)
    ensure_depot_tools(root, depot_tools)

    env = network_env()
    env["PATH"] = depot_tools + os.pathsep + env["PATH"]
    env["DEPOT_TOOLS_UPDATE"] = "0"
    run(
        [os.path.join(depot_tools, "ensure_bootstrap")],
        root,
        env,
        retry_delays=DOWNLOAD_RETRY_DELAYS,
        timeout=600,
    )
    return env

def network_env():
    env = os.environ.copy()
    env.setdefault("GIT_HTTP_LOW_SPEED_LIMIT", "1024")
    env.setdefault("GIT_HTTP_LOW_SPEED_TIME", "60")
    return env


def ensure_depot_tools(root, depot_tools):
    if os.path.isdir(depot_tools):
        if git_revision(depot_tools) == DEPOT_TOOLS_REVISION:
            return
        log("Replacing depot_tools checkout that is not at the pinned revision.")
        shutil.rmtree(depot_tools)

    partial_checkout = depot_tools + ".part"
    shutil.rmtree(partial_checkout, ignore_errors=True)
    os.makedirs(partial_checkout)

    try:
        env = network_env()
        run(["git", "init", "--quiet"], partial_checkout, env)
        run(["git", "remote", "add", "origin", DEPOT_TOOLS_GIT_URL], partial_checkout, env)
        run(
            ["git", "fetch", "--depth", "1", "origin", DEPOT_TOOLS_REVISION],
            partial_checkout,
            env,
            retry_delays=DOWNLOAD_RETRY_DELAYS,
            timeout=600,
        )
        run(["git", "checkout", "--detach", "FETCH_HEAD"], partial_checkout, env)
        os.replace(partial_checkout, depot_tools)
    finally:
        shutil.rmtree(partial_checkout, ignore_errors=True)


def git_revision(directory):
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=directory,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def sync_pdfium(root, env):
    checkout = os.path.join(root, PDFIUM_SOURCE_DIR)
    source = os.path.join(checkout, "pdfium")

    os.makedirs(checkout, exist_ok=True)

    if not os.path.isfile(os.path.join(checkout, ".gclient")):
        run(
            [
                "gclient",
                "config",
                "--unmanaged",
                PDFIUM_GIT_URL,
                "--custom-var",
                "checkout_configuration=minimal",
            ],
            checkout,
            env,
            retry_delays=DOWNLOAD_RETRY_DELAYS,
            timeout=600,
        )

        with open(os.path.join(checkout, ".gclient"), "a") as config:
            config.write("target_os = ['android']\n")

    reset_patched_repositories(source, env)
    run(
        ["gclient", "sync", "-r", PDFIUM_REVISION, "--no-history", "--shallow"],
        checkout,
        env,
        retry_delays=DOWNLOAD_RETRY_DELAYS,
        timeout=3600,
    )
    apply_pdfium_patches(root, source, env)

    return source


def reset_patched_repositories(source, env):
    if not os.path.isdir(source):
        return

    for repository in patched_repositories(source, env):
        run(["git", "reset", "--hard", "HEAD"], repository, env)
        delete_patch_artifacts(repository)

    log("Reset PDFium source before sync.")


def patched_repositories(source, env):
    repositories = []

    for _, patch_dir in PDFIUM_PATCHES:
        directory = get_patch_cwd(source, patch_dir)
        repository = git_repository_root(directory, env)

        if repository is not None and repository not in repositories:
            repositories.append(repository)

    return repositories


def git_repository_root(directory, env):
    if not os.path.isdir(directory):
        return None

    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=directory,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )

    if result.returncode != 0:
        return None

    return os.path.abspath(result.stdout.strip())


def delete_patch_artifacts(directory):
    for root, dirs, files in os.walk(directory):
        if ".git" in dirs:
            dirs.remove(".git")

        for file in files:
            if file.endswith(".orig") or file.endswith(".rej"):
                os.remove(os.path.join(root, file))


def apply_pdfium_patches(root, source, env):
    for patch_file, patch_dir in PDFIUM_PATCHES:
        apply_patch(root, source, patch_file, patch_dir, env)


def apply_patch(root, source, patch_file, patch_dir, env):
    patch_path = get_patch_path(root, patch_file)
    patch_cwd = get_patch_cwd(source, patch_dir)

    if not os.path.isfile(patch_path):
        error(f"Missing PDFium patch file: {patch_path}")

    if not os.path.isdir(patch_cwd):
        error(f"Missing PDFium patch directory: {patch_cwd}")

    run(["patch", "--forward", "--batch", "-p1", "-i", patch_path], patch_cwd, env)
    log(f"Applied patch: {patch_file}")


def get_patch_path(root, patch_file):
    return os.path.join(root, patch_file)


def get_patch_cwd(source, patch_dir):
    return os.path.join(source, patch_dir)


def build_arch(source, arch, env):
    log(f"Building PDFium from source for {ARCH_NAMES[arch]}.")
    out_dir = os.path.join("out", ARCH_NAMES[arch])

    write_gn_args(os.path.join(source, out_dir), arch)
    run(["gn", "gen", out_dir], source, env)
    run(["ninja", "-C", out_dir, "pdfium"], source, env)
    install_lib(os.path.join(source, out_dir), arch)

    log(f"Finished building PDFium for {ARCH_NAMES[arch]}.")
    log("------------------------------------")


def write_gn_args(out_dir, arch):
    os.makedirs(out_dir, exist_ok=True)

    args = PDFIUM_GN_ARGS + [f'target_cpu = "{PDFIUM_GN_CPU_NAMES[arch]}"']
    with open(os.path.join(out_dir, "args.gn"), "w") as args_file:
        args_file.write("\n".join(args) + "\n")


def install_lib(out_dir, arch):
    built_lib = os.path.join(out_dir, "libpdfium.so")

    if not os.path.isfile(built_lib):
        error(f"Build produced no library at {built_lib}")

    lib_path = get_lib_path(arch, FILE_NAMES[Lib.pdfium] + LIB_EXTENSION, levels_up=1)

    delete_file_if_exists(lib_path)
    os.makedirs(os.path.dirname(lib_path), exist_ok=True)
    shutil.copy(built_lib, lib_path)

    log(f"Installed {lib_path}.")


def run(command, cwd, env, retry_delays=(), timeout=None):
    attempts = len(retry_delays) + 1
    failure = None
    for attempt in range(attempts):
        log("Run: " + " ".join(command))
        try:
            result = subprocess.run(command, cwd=cwd, env=env, timeout=timeout)
            if result.returncode == 0:
                return
            failure = f"failed with code {result.returncode}"
        except subprocess.TimeoutExpired:
            failure = f"timed out after {timeout} seconds"

        if attempt < len(retry_delays):
            delay = retry_delays[attempt]
            log(f"'{command[0]}' {failure}; retrying in {delay} seconds.")
            time.sleep(delay)

    error(f"'{command[0]}' {failure} after {attempts} attempts")


if __name__ == "__main__":
    build_pdfium_from_source(ALL_ARCHES)