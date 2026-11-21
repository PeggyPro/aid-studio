/** Retain exact sampled motion, without allocating one value per static frame. */
export class AnimationSamples {
  readonly times: number[] = [];
  readonly values: number[] = [];
  private previousTime = 0;
  private previous: number[] | undefined;

  finish() {
    if (this.previous && this.times[this.times.length - 1] !== this.previousTime) {
      this.times.push(this.previousTime);
      this.values.push(...this.previous);
    }
  }

  append(time: number, value: readonly number[]) {
    if (this.previous && this.previous.length !== value.length)
      throw new Error("动画采样期间属性维数发生变化");
    const changed = !this.previous || value.some((v, i) => v !== this.previous![i]);
    if (changed) {
      // Keep the end of a hold before the next change, so interpolation cannot
      // turn an extended hold into a slow movement.
      if (this.previous && this.times[this.times.length - 1] !== this.previousTime) {
        this.times.push(this.previousTime);
        this.values.push(...this.previous);
      }
      this.times.push(time);
      this.values.push(...value);
      this.previous = Array.from(value);
    }
    this.previousTime = time;
  }
}
