export interface RecipientOption {
  label: string
  value: string
  description?: string
}

export function createRecipientRemoteOptions(
  loadOptions: (keyword: string) => Promise<RecipientOption[]>,
) {
  return {
    async search({ keyword }: { keyword: string; page: number; size: number }) {
      const options = await loadOptions(keyword)
      return { options, page: 1, size: Math.max(options.length, 20), total: options.length }
    },
    async findOptionsByValues(values: string[]) {
      return Promise.all(
        values.map(async (value) => {
          try {
            const options = await loadOptions(value)
            return options.find((option) => option.value === value) ?? { label: value, value }
          } catch {
            return { label: value, value }
          }
        }),
      )
    },
  }
}
