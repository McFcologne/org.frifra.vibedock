-- Pandoc filter for the PDF manual (scripts/docs.sh): a heading whose section starts with a little text and then a
-- picture moves to the next page together with them when they do not fit on the current one, instead of leaving the
-- heading and its first lines alone at the bottom of a page. The space needed is estimated from the text length and
-- the picture's size (its width in the Markdown and its pixel dimensions); \needspace breaks the page if it is short.

local LINE_WIDTH = 466    -- pt: A4 minus the margins of docs.sh (2.3 cm each side)
local MAX_HEIGHT = 496    -- pt: 0.72 \textheight, the cap in manual.tex
local LINE = 13.8         -- pt: 10 pt type with \linestretch 1.15
local CHARS_PER_LINE = 95

-- MANUAL.md sizes its pictures with <img src=… width="…%" alt=…>, because GitHub shows ![…](…){width=…} as text.
-- For the PDF they become pandoc images again: alone in a paragraph a figure with the alt text as caption.
local function html_image(inline)
  if inline.t ~= 'RawInline' or inline.format ~= 'html' or not inline.text:match('^<img%s') then return nil end
  local function attr(name) return inline.text:match('%s' .. name .. '="([^"]*)"') end
  if not attr('src') then return nil end
  return pandoc.Image(attr('alt') or '', attr('src'), '', pandoc.Attr('', {}, {width = attr('width')}))
end

function Para(para)
  if not FORMAT:match('latex') then return nil end
  local images, other = {}, false
  local content = para.content:map(function(inline)
    local image = html_image(inline)
    if image then table.insert(images, image); return image end
    if inline.t ~= 'Space' and inline.t ~= 'SoftBreak' then other = true end
    return inline
  end)
  if #images == 0 then return nil end
  if #images == 1 and not other then
    return pandoc.Figure({pandoc.Plain({images[1]})}, {pandoc.Plain(images[1].caption)})
  end
  return pandoc.Para(content)
end

local function png_size(path)
  local file = io.open(path, 'rb'); if not file then return nil end
  local header = file:read(24); file:close()
  if not header or #header < 24 or header:sub(2, 4) ~= 'PNG' then return nil end
  local function u32(i) local a, b, c, d = header:byte(i, i + 3); return ((a * 256 + b) * 256 + c) * 256 + d end
  return u32(17), u32(21)
end

local function text_height(block)
  local text = pandoc.utils.stringify(block)
  local lines = math.max(1, math.ceil(#text / CHARS_PER_LINE))
  if block.t == 'BulletList' or block.t == 'OrderedList' then lines = lines + #block.content * 0.3 end
  return lines * LINE + 6
end

local function figure_height(figure)
  local image
  figure:walk({Image = function(i) image = image or i end})
  if not image then return nil end
  local w, h = png_size(image.src)
  for _, dir in ipairs(PANDOC_STATE.resource_path or {}) do
    if w then break end
    w, h = png_size(dir .. '/' .. image.src)
  end
  if not w then return nil end
  local share = tonumber((image.attributes.width or '100%'):match('^(%d+)%%$') or '100') / 100
  local height = math.min(LINE_WIDTH * share * h / w, MAX_HEIGHT)
  return height + 3 * LINE  -- caption and spacing
end

function Pandoc(doc)
  if not FORMAT:match('latex') then return nil end
  local blocks, out = doc.blocks, {}
  for i, block in ipairs(blocks) do
    if block.t == 'Header' and block.level >= 2 then
      local need, j = 2.5 * LINE, i + 1
      while blocks[j] and blocks[j].t ~= 'Header' and j <= i + 4 do
        local next = blocks[j]
        if next.t == 'Figure' then
          local height = figure_height(next)
          if height then table.insert(out, pandoc.RawBlock('latex', string.format('\\needspace{%dpt}', math.ceil(need + height)))) end
          break
        end
        need = need + text_height(next); j = j + 1
      end
    end
    table.insert(out, block)
  end
  doc.blocks = out
  return doc
end
