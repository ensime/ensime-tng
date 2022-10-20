-- https://gist.github.com/amiralies/958278cc181c84acb22fa064222717f5
local configs = require 'lspconfig.configs'
local lspconfig = require 'lspconfig'

-- TODO add the "ensime.import uri line column" action

-- Check if the config is already defined (useful when reloading this file)
if not configs.ensime then
  configs.ensime = {
    default_config = {
      cmd = {
        'java', '-jar', os.getenv("HOME") .. '/.cache/ensime/lib/ensime-lsp.jar'
      },
      filetypes = {'scala'},
      root_dir = function(fname)
        -- this could be set to / since ensime-tng is project agnostic
        return lspconfig.util.find_git_ancestor(fname)
      end,
      settings = {}
    }
  }
end

-- call `setup()` to enable the FileType autocmd
require'lspconfig'.ensime.setup {}
