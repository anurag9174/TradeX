
/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{js,jsx,ts,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        // Professional trading terminal surfaces
        ink: {
          950: '#0B0E11',
          900: '#0F1318',
          850: '#131820',
          800: '#191F27',
          700: '#252C35',
          600: '#343C46',
          500: '#848E9C',
        },

        // Teal: positive movement and primary actions
        accent: {
          DEFAULT: '#26A69A',
          hover: '#2BBBAD',
          dark: '#1D897F',
        },

        // Red: negative movement and sell actions
        danger: {
          DEFAULT: '#EF5350',
          soft: '#FF6B6B',
        },

        warning: '#F0B429',
        info: '#64B5F6',

        // Explicit semantic colors for trading UI
        market: {
          bg: '#0B0E11',
          surface: '#131820',
          elevated: '#191F27',
          border: '#252C35',
          muted: '#848E9C',
          text: '#E6E8EB',
          profit: '#26A69A',
          loss: '#EF5350',
        },
      },

      fontFamily: {
        sans: [
          'Inter',
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'sans-serif',
        ],
        mono: [
          'ui-monospace',
          'SFMono-Regular',
          'Menlo',
          'monospace',
        ],
      },

      boxShadow: {
        // Avoid excessive glow; use subtle depth.
        panel: '0 2px 8px rgba(0, 0, 0, 0.18)',
      },

      borderRadius: {
        panel: '8px',
      },

      transitionDuration: {
        120: '120ms',
      },

      keyframes: {
        'slide-in': {
          from: {
            opacity: '0',
            transform: 'translateY(-6px)',
          },
          to: {
            opacity: '1',
            transform: 'translateY(0)',
          },
        },
        'fade-in': {
          from: { opacity: '0' },
          to: { opacity: '1' },
        },
      },

      animation: {
        'slide-in': 'slide-in 180ms ease-out',
        'fade-in': 'fade-in 180ms ease-out',
      },
    },
  },

  plugins: [],
};
