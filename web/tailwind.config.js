/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  theme: {
    extend: {
      colors: {
        brand: '#ff4d6d',
        ink: '#1f1d1c',
        paper: '#f7f4f2'
      }
    }
  },
  plugins: []
}
