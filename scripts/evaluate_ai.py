"""Run retained MOCK or explicitly approved LIVE evaluation through the Java adapter.
Use a terminal with Java 21; Windows users first source scripts/local-env.ps1.
No API keys are read or printed by this launcher.
"""
import argparse
import pathlib
import subprocess
import os

parser = argparse.ArgumentParser()
parser.add_argument("--live", action="store_true", help="Explicit opt-in; requires configured model/key/budget and AI_LIVE_APPROVED=true")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
if args.live and os.environ.get("AI_LIVE_APPROVED", "").lower() != "true":
    raise SystemExit("LIVE is not approved. Configure environment locally; never paste a key in chat.")
wrapper = str(root / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
raise SystemExit(subprocess.call([wrapper, "-B", "-ntp", "-Dtest=AiEvaluation",
                                 "-Dai.eval.live=" + str(args.live).lower(), "test"], cwd=root))
