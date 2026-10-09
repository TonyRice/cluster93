// The MongoDB driver against a real MongoDB (D-116): inserts, queries, updates, aggregation, indexes, cursors and
// errors. TEST_MONGO is the server. Runs the same under Node.
import { MongoClient } from 'mongodb';

export async function run() {
  const out = {};
  const client = new MongoClient(process.env.TEST_MONGO, { serverSelectionTimeoutMS: 10000 });
  await client.connect();
  const items = client.db('app').collection('items');
  await items.drop().catch(() => {});
  const inserted = await items.insertMany([
    { name: 'apple', price: 1.25, tags: ['fruit', 'red'], stock: 10 },
    { name: 'bread', price: 3.5, tags: ['bakery'], stock: 4 },
    { name: 'cheese', price: 7, tags: ['dairy'], stock: 0 },
  ]);
  out.insertedCount = inserted.insertedCount;
  out.found = await items.find({ price: { $gt: 2 } }, { projection: { _id: 0, name: 1, price: 1 } }).sort({ price: -1 }).toArray();
  out.updated = (await items.updateOne({ name: 'bread' }, { $inc: { stock: 6 }, $push: { tags: 'fresh' } })).modifiedCount;
  out.bread = await items.findOne({ name: 'bread' }, { projection: { _id: 0 } });
  out.count = await items.countDocuments({ stock: { $gt: 0 } });
  out.aggregate = await items.aggregate([{ $unwind: '$tags' }, { $group: { _id: '$tags', n: { $sum: 1 } } }, { $sort: { _id: 1 } }]).toArray();
  out.index = await items.createIndex({ name: 1 }, { unique: true });
  out.duplicate = await items.insertOne({ name: 'apple' }).then(() => 'ok', (e) => [e.name, e.code]);
  const names = [];
  for await (const doc of items.find({}, { projection: { _id: 0, name: 1 } }).sort({ name: 1 }).batchSize(1)) names.push(doc.name);
  out.cursor = names;
  out.deleted = (await items.deleteMany({ stock: 0 })).deletedCount;
  await client.close();
  return out;
}
