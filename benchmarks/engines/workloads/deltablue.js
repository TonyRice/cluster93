// Pure computation: Octane's DeltaBlue (vendor/deltablue.js, BSD), a one-way constraint solver.
function iteration() {
  for (let k = 0; k < 300; k++) deltaBlue();
}
