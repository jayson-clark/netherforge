import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ColorField, NumberInput, Vec3Field } from './fields'

afterEach(cleanup)

describe('inspector fields', () => {
  it('commits a number once, on Enter, not per keystroke', () => {
    const onChange = vi.fn()
    render(<NumberInput label="Gravity" value={32} onChange={onChange} />)
    const input = screen.getByRole('textbox', { name: 'Gravity' })
    fireEvent.focus(input)
    fireEvent.change(input, { target: { value: '1' } })
    fireEvent.change(input, { target: { value: '10' } })
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledExactlyOnceWith(10)
  })

  it('clearing a number means "back to the default"', () => {
    const onChange = vi.fn()
    render(<NumberInput label="Gravity" value={32} onChange={onChange} />)
    const input = screen.getByRole('textbox', { name: 'Gravity' })
    fireEvent.focus(input)
    fireEvent.change(input, { target: { value: '' } })
    fireEvent.blur(input)
    expect(onChange).toHaveBeenCalledExactlyOnceWith(undefined)
  })

  it('commits a colour lower-cased, puts back one written another way, and clears it', () => {
    const onChange = vi.fn()
    const { rerender } = render(<ColorField label="Sky" value="#112233" onChange={onChange} />)
    const input = screen.getByRole('textbox', { name: 'Sky' })
    fireEvent.change(input, { target: { value: 'blue' } })
    fireEvent.blur(input)
    expect(onChange).not.toHaveBeenCalled()
    expect(input).toHaveProperty('value', '#112233')
    fireEvent.change(input, { target: { value: '#AABBCC' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenLastCalledWith('#aabbcc')
    // The picker commits as it's picked.
    fireEvent.change(screen.getByLabelText('Sky picker'), { target: { value: '#00ff00' } })
    expect(onChange).toHaveBeenLastCalledWith('#00ff00')
    fireEvent.click(screen.getByRole('button', { name: 'Clear Sky' }))
    expect(onChange).toHaveBeenLastCalledWith(undefined)
    rerender(<ColorField label="Sky" value={undefined} onChange={onChange} />)
    expect(screen.queryByRole('button', { name: 'Clear Sky' })).toBeNull()
  })

  it('ignores text that is not a number', () => {
    const onChange = vi.fn()
    render(<NumberInput label="Gravity" value={32} onChange={onChange} />)
    const input = screen.getByRole('textbox', { name: 'Gravity' })
    fireEvent.focus(input)
    fireEvent.change(input, { target: { value: 'abc' } })
    fireEvent.blur(input)
    expect(onChange).not.toHaveBeenCalled()
    expect(input).toHaveProperty('value', '32')
  })

  it('scrubbing a vector label is one gesture', () => {
    const onChange = vi.fn()
    const onGestureStart = vi.fn()
    const onGestureEnd = vi.fn()
    render(
      <Vec3Field
        label="Translation"
        value={[0, 1, 0]}
        defaultValue={[0, 0, 0]}
        step={1}
        onChange={onChange}
        onGestureStart={onGestureStart}
        onGestureEnd={onGestureEnd}
      />,
    )
    fireEvent.pointerDown(screen.getByText('Translation'), { clientX: 100 })
    for (const x of [104, 108, 112]) fireEvent.pointerMove(window, { clientX: x })
    fireEvent.pointerUp(window)
    expect(onGestureStart).toHaveBeenCalledTimes(1)
    expect(onGestureEnd).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledTimes(3)
    expect(onChange).toHaveBeenLastCalledWith([3, 1, 0])
  })
})
