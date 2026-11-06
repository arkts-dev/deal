"""Hypothesis traces executed as typed DEAL against all three production artifacts.

Run after Java test compilation: python3 test/boundary_stateful.py
Install test-only dependencies with: python3 -m pip install -r test/requirements.txt
Hypothesis shrinks the action trace and scalar inputs; assertion failures include
its reproducer and the exact generated source, not a backend-majority verdict.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

# Keep Hypothesis examples/constants out of the tracked source tree.
os.environ.setdefault("HYPOTHESIS_STORAGE_DIRECTORY", str(
    Path(__file__).resolve().parents[1] / "scratch-review/hypothesis"))

from hypothesis import settings, strategies as st
from hypothesis.stateful import RuleBasedStateMachine, initialize, precondition, rule

ROOT = Path(__file__).resolve().parents[1]


class BoundaryMachine(RuleBasedStateMachine):
    @initialize(initial=st.integers(-8, 8))
    def construct(self, initial):
        self.value = initial
        self.valid = True
        self.actions = [
            f"let a:number[]=[{initial},99];let alias=a;let destination:number[]=[42];let untouched=destination;let returns:number=0;",
            "host.keep(a);check((host.echo(a) as number[])===a);",
            "let get=function():number[]{returns=returns+1;return a;};",
            "let effects:string=\"\";",
            "let receiver=function():number[]{effects=effects+\"R\";return destination;};",
            "let index=function():number{effects=effects+\"I\";return 9;};",
            "let rhs=function():number{effects=effects+\"V\";return 7;};",
        ]
        # Every example covers the full lifecycle; subsequent rules explore
        # repeat/reorder histories instead of hoping random selection hits it.
        self.callback(2)
        self.mutate(initial + 1)
        self.reject()
        self.repair(initial)
        self.retry()

    def snapshot(self):
        self.actions.append(
            f"check(a===alias && alias[0]==={self.value} && alias[1]===99);"
            "check((host.echo(alias) as number[])===a);check(destination[0]===42);"
        )

    @precondition(lambda self: self.valid)
    @rule(delta=st.integers(-8, 8))
    def callback(self, delta):
        self.value += delta
        self.actions.append(
            "let result=host.callback(function(x:number):number{a[0]=a[0]+x;return a[0];},"
            f"{delta});check(result==={self.value});"
        )
        self.snapshot()

    @precondition(lambda self: self.valid)
    @rule(value=st.integers(-8, 8))
    def mutate(self, value):
        self.value = value
        self.actions.append(f"host.mutate({value});")
        self.snapshot()

    @precondition(lambda self: self.valid)
    @rule()
    def reject(self):
        self.valid = False
        self.actions.append(
            'host.corrupt();let rejected=false;let before=returns;'
            # Return validation rechecks the array's elements: E8003, not
            # the E8001 projection of an explicit checked assertion.
            'try{destination=get();}catch(e){check((e as Error).code==="E8003");rejected=true;}'
            'check(rejected && returns===before+1 && destination===untouched && destination[0]===42 && destination.length===1);'
            # Independent order control: all operand effects happen before
            # bounds validation, and failure never commits the replacement.
            'effects="";rejected=false;'
            'try{receiver()[index()]=rhs();}catch(e){check((e as Error).code==="E8002");rejected=true;}'
            'check(rejected && effects==="RIV" && destination===untouched && destination[0]===42 && destination.length===1);'
        )

    @precondition(lambda self: not self.valid)
    @rule(value=st.integers(-8, 8))
    def repair(self, value):
        self.valid = True
        self.value = value
        self.actions.append(f"host.mutate({value});")
        self.snapshot()

    @precondition(lambda self: self.valid)
    @rule()
    def retry(self):
        self.actions.append("check(get()===a);check((host.echo(a) as number[])===alias);")
        self.snapshot()

    def teardown(self):
        source = (
            'import * as c from "std/console";import * as host from "host/probe";'
            'export function main():null{let checks:string="";'
            'let check=function(ok:boolean):void{checks=checks+".";'
            'if(!ok){c.log(checks);throw {code:"TEST_FAIL",message:"stateful"};}};'
            # Give each action its own lexical block so repeated declarations
            # stay valid while shared bindings live in main's outer scope.
            + "".join(self.actions[:7])
            + "".join("{" + action + "}" for action in self.actions[7:])
            + 'c.log("STATE_OK");return null;}'
        )
        with tempfile.TemporaryDirectory(prefix="deal-hypothesis-") as temp:
            temp = Path(temp)
            (temp / "main.deal").write_text(source)
            (temp / "expected.txt").write_text("STATE_OK\n")
            fixture = ROOT / "test/conformance/host-fixtures/boundaries/boundary_stateful"
            for extension in ("d.deal", "java", "js", "lua"):
                shutil.copyfile(str(fixture) + "." + extension, temp / ("probe." + extension))
            cp = os.pathsep.join(str(ROOT / p) for p in ("build", "build/test-classes"))
            result = subprocess.run(
                ["java", "-XX:ActiveProcessorCount=2", "-ea", "-cp", cp,
                 "deal.test.CompositionProductionTest", str(temp / "main.deal"),
                 str(temp / "expected.txt"), str(temp)],
                cwd=ROOT, text=True, capture_output=True, timeout=180,
            )
            assert result.returncode == 0, result.stdout + result.stderr + "\nDEAL source:\n" + source


if __name__ == "__main__":
    from hypothesis.stateful import run_state_machine_as_test
    run_state_machine_as_test(BoundaryMachine, settings=settings(
        max_examples=4, stateful_step_count=6, deadline=None, derandomize=True,
    ))
    print("Hypothesis boundary state machine: all JS/LuaJIT/JVM traces passed")
