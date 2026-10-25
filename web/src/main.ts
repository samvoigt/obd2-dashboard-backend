import { mount } from 'svelte'
import './app.css'
import App from './App.svelte'
import { applyTheme, loadTheme } from './lib/theme'

// Before the first paint, so a bright viewer never sees a flash of dark (M31.6).
applyTheme(loadTheme())
mount(App, { target: document.getElementById('app')! })
