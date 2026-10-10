// Our polyfills: URL and URLSearchParams.
function iteration(i) {
  let n = 0;
  for (let k = 0; k < 5000; k++) {
    const url = new URL(`https://user:pw@example.com:8443/a/b/../c/${k}?q=${k}&tag=x&tag=y#frag`);
    url.searchParams.append('i', String(i));
    n += url.pathname.length + url.searchParams.getAll('tag').length + url.href.length;
  }
  return n;
}
