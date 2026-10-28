;; The showcase's Mandelbrot renderer (examples/showcase/wasm/mandelbrot), hand-written.
;; render(w, h, zoom) writes one byte per pixel, '0' for inside the set and '1' to '9' for
;; escape-time bands, and returns where the text starts and how long it is, packed in an i64.
(module
  (memory (export "memory") 2)
  (global $heap (mut i32) (i32.const 1024))
  (func $alloc (export "alloc") (param $size i32) (result i32)
    (local $at i32)
    (local.set $at (global.get $heap))
    (global.set $heap (i32.add (global.get $heap) (local.get $size)))
    (local.get $at))
  (func (export "render") (param $w i32) (param $h i32) (param $zoom i32) (result i64)
    (local $x i32) (local $y i32) (local $i i32) (local $out i32) (local $n i32)
    (local $scale f64) (local $cx f64) (local $cy f64) (local $zx f64) (local $zy f64)
    (local $t f64) (local $midx f64) (local $midy f64) (local $towards f64)
    (global.set $heap (i32.const 1024))
    (local.set $n (i32.mul (local.get $w) (local.get $h)))
    (local.set $out (call $alloc (local.get $n)))
    ;; Zooming moves the middle from (-0.5, 0) towards a spiral at (-0.7453, 0.1127).
    (local.set $towards (f64.sub (f64.const 1) (f64.div (f64.const 1) (f64.convert_i32_s (local.get $zoom)))))
    (local.set $midx (f64.add (f64.const -0.5) (f64.mul (f64.const -0.2453) (local.get $towards))))
    (local.set $midy (f64.mul (f64.const 0.1127) (local.get $towards)))
    (local.set $scale (f64.div (f64.const 3) (f64.mul (f64.convert_i32_s (local.get $w)) (f64.convert_i32_s (local.get $zoom)))))
    (local.set $y (i32.const 0))
    (block $rows_done
      (loop $rows
        (br_if $rows_done (i32.ge_s (local.get $y) (local.get $h)))
        (local.set $x (i32.const 0))
        (block $cols_done
          (loop $cols
            (br_if $cols_done (i32.ge_s (local.get $x) (local.get $w)))
            (local.set $cx (f64.add (local.get $midx)
              (f64.mul (local.get $scale) (f64.convert_i32_s (i32.sub (local.get $x) (i32.div_s (local.get $w) (i32.const 2)))))))
            (local.set $cy (f64.add (local.get $midy)
              (f64.mul (local.get $scale) (f64.convert_i32_s (i32.sub (local.get $y) (i32.div_s (local.get $h) (i32.const 2)))))))
            (local.set $zx (f64.const 0))
            (local.set $zy (f64.const 0))
            (local.set $i (i32.const 0))
            (block $escaped
              (loop $iterate
                (br_if $escaped (i32.ge_s (local.get $i) (i32.const 300)))
                (br_if $escaped (f64.gt
                  (f64.add (f64.mul (local.get $zx) (local.get $zx)) (f64.mul (local.get $zy) (local.get $zy)))
                  (f64.const 4)))
                (local.set $t (f64.add (f64.sub (f64.mul (local.get $zx) (local.get $zx)) (f64.mul (local.get $zy) (local.get $zy))) (local.get $cx)))
                (local.set $zy (f64.add (f64.mul (f64.const 2) (f64.mul (local.get $zx) (local.get $zy))) (local.get $cy)))
                (local.set $zx (local.get $t))
                (local.set $i (i32.add (local.get $i) (i32.const 1)))
                (br $iterate)))
            (i32.store8
              (i32.add (local.get $out) (i32.add (i32.mul (local.get $y) (local.get $w)) (local.get $x)))
              (if (result i32) (i32.ge_s (local.get $i) (i32.const 300))
                (then (i32.const 48))
                (else (i32.add (i32.const 49) (i32.rem_u (i32.div_u (local.get $i) (i32.const 3)) (i32.const 9))))))
            (local.set $x (i32.add (local.get $x) (i32.const 1)))
            (br $cols)))
        (local.set $y (i32.add (local.get $y) (i32.const 1)))
        (br $rows)))
    (i64.or
      (i64.shl (i64.extend_i32_u (local.get $out)) (i64.const 32))
      (i64.extend_i32_u (local.get $n))))
)
