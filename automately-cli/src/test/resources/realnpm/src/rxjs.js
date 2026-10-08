import { from, firstValueFrom } from 'rxjs';
import { map, filter, reduce } from 'rxjs/operators';
export const run = () => firstValueFrom(from([1, 2, 3, 4, 5]).pipe(filter(n => n % 2 === 1), map(n => n * 10), reduce((a, b) => a + b, 0)));
