// Pure computation: Octane's Richards (vendor/richards.js, BSD), an OS task scheduler simulation.
function iteration() {
  for (let k = 0; k < 300; k++) runRichards();
}
