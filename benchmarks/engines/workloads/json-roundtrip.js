// Pure computation: JSON.stringify and JSON.parse of a ~1 MB document.
let doc;
function setup() {
  doc = { items: [] };
  for (let i = 0; i < 5000; i++) {
    doc.items.push({ id: i, name: `item-${i}`, tags: ['a', 'b', 'c', String(i % 7)], price: i * 1.25, active: i % 2 === 0,
      nested: { x: i, y: [i, i + 1, i + 2], label: 'x'.repeat(i % 40) } });
  }
}
function iteration() {
  for (let k = 0; k < 5; k++) {
    const text = JSON.stringify(doc);
    const back = JSON.parse(text);
    if (back.items.length !== 5000) throw new Error('bad round trip');
  }
}
